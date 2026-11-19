package ai.unalome.pocket.llamaengine

import ai.unalome.pocket.chatcore.ChatEngine
import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ChatTurn
import ai.unalome.pocket.chatcore.EngineCapabilities
import ai.unalome.pocket.chatcore.GenerationEvent
import ai.unalome.pocket.chatcore.GenerationOptions
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Memory an image turn needs besides the image reader's weights: the encoder's work buffers. */
private const val IMAGE_WORK_BYTES = 512L shl 20

/**
 * A GGUF model run by llama.cpp on the CPU, optionally with its image reader (an mmproj file).
 *
 * One reply at a time; [load], [reply] and [unload] are serialised by a mutex, and all native work
 * runs on the backend's single thread. Cancelling a load or a reply aborts the native work
 * promptly; [unload] during a reply cancels the reply first, so the model is never freed under it.
 *
 * The image reader is loaded on the first turn with an image, after checking that the phone has
 * the memory free for it, and stays loaded with the model.
 */
class LlamaEngine internal constructor(
    private val modelFile: File,
    override val displayName: String,
    private val config: LlamaConfig,
    /** From the catalog: the model was trained to reason before answering. */
    private val modelSupportsThinking: Boolean,
    private val nativeLibraryDir: String,
    /** The model's image reader; images are supported while this file exists. */
    private val projectorFile: File?,
    /** Memory the app may still use before Android starts stopping things; `null` if unknown. */
    private val availableMemoryBytes: () -> Long?,
    private val imageSize: (String) -> Pair<Int, Int>?,
    private val backend: LlamaBackend,
) : ChatEngine {
    constructor(
        modelFile: File,
        displayName: String,
        config: LlamaConfig,
        modelSupportsThinking: Boolean,
        nativeLibraryDir: String,
        projectorFile: File? = null,
        availableMemoryBytes: () -> Long? = { null },
    ) : this(
        modelFile, displayName, config, modelSupportsThinking, nativeLibraryDir, projectorFile,
        availableMemoryBytes, ::readImageSize, NativeBackend,
    )

    private val mutex = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var contextTokens = config.contextTokens
    @Volatile private var templateSupportsThinking = true
    @Volatile private var projectorLoaded = false
    @Volatile private var activeReply: Job? = null

    override val capabilities: EngineCapabilities
        get() = EngineCapabilities(
            supportsVision = projectorFile?.isFile == true,
            maxContextTokens = contextTokens,
            supportsThinking = modelSupportsThinking && templateSupportsThinking,
        )

    val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(progress: (Double) -> Unit) = mutex.withLock {
        if (handle != 0L) return@withLock
        if (!modelFile.isFile) throw LlamaException("The model file is missing. Download the model again.")
        val cancelled = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        try {
            abortable(backend.dispatcher, onCancel = { cancelled.set(true) }) {
                if (handle != 0L) return@abortable
                backend.ensureInitialised(nativeLibraryDir)
                val loaded = backend.load(modelFile.absolutePath, config.contextTokens, config.threads) { value ->
                    progress(value.toDouble())
                    !cancelled.get()
                }
                // Kept even if the caller was cancelled meanwhile, so the next load is instant.
                contextTokens = backend.contextSize(loaded)
                templateSupportsThinking = backend.supportsThinking(loaded)
                projectorLoaded = false
                handle = loaded
            }
        } catch (e: LlamaException) {
            if (cancelled.get()) throw CancellationException("Loading was cancelled.")
            throw e
        } catch (e: UnsatisfiedLinkError) {
            throw LlamaException("The on-device engine could not start on this phone (${e.message}).")
        }
        Log.i(TAG, "Loaded ${modelFile.name} in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        progress(1.0)
    }

    override suspend fun unload() {
        handle.takeIf { it != 0L }?.let(backend::abort)
        activeReply?.cancel()
        mutex.withLock {
            withContext(NonCancellable + backend.dispatcher) {
                val loaded = handle
                if (loaded != 0L) {
                    handle = 0L
                    projectorLoaded = false
                    backend.free(loaded)
                }
            }
        }
    }

    override fun reply(turn: ChatTurn, history: List<ChatTurn>, options: GenerationOptions): Flow<GenerationEvent> = flow {
        mutex.withLock {
            val loaded = handle.takeIf { it != 0L } ?: throw LlamaException("The model is not loaded.")
            activeReply = currentCoroutineContext().job
            try {
                val vision = capabilities.supportsVision
                if (turn.images.isNotEmpty() && !vision) {
                    throw LlamaException(
                        "$displayName cannot read images. Choose a model with image support, or send the message without the image.",
                    )
                }
                // A model without its image reader still answers about the text of earlier turns.
                val all = (history + turn).map { item ->
                    item.copy(images = if (vision) item.images.filter { File(it).isFile } else emptyList())
                }
                if (all.any { it.images.isNotEmpty() }) prepareImages(loaded)

                val thinking = options.thinking && capabilities.supportsThinking
                val context = contextTokens
                val turns = withContext(backend.dispatcher) {
                    ContextWindow.fit(all, context, ContextWindow.reserve(options.maxTokens, context)) { candidate ->
                        backend.countTokens(loaded, encode(candidate, thinking, withSizes = true))
                    }
                }
                val prompt = encode(turns, thinking, withSizes = false)
                // Cleared here, before the dispatch, never inside `start`: a Stop that arrives in
                // between then still stops the prompt from being read.
                backend.resetAbort(loaded)
                currentCoroutineContext().ensureActive()
                val promptTokens = abortable(backend.dispatcher, onCancel = { backend.abort(loaded) }) {
                    backend.start(loaded, prompt, options.temperature.toFloat(), options.maxTokens, Random.nextInt())
                }
                currentCoroutineContext().ensureActive()
                if (promptTokens < 0) throw CancellationException("The reply was stopped.")
                backend.lastPromptStats(loaded)?.let(::logPrompt)

                val startedAt = System.nanoTime()
                var generated = 0
                fun rate(): Double {
                    val seconds = (System.nanoTime() - startedAt) / 1e9
                    return if (seconds > 0) generated / seconds else 0.0
                }
                while (true) {
                    val step = abortable(backend.dispatcher, onCancel = { backend.abort(loaded) }) { backend.next(loaded) }
                    step.reasoning?.let { emit(GenerationEvent.Reasoning(it.decodeToString())) }
                    step.content?.let { emit(GenerationEvent.Token(it.decodeToString())) }
                    when (step.status) {
                        NativeStep.CONTINUE -> {
                            generated++
                            if (generated % 4 == 0) emit(GenerationEvent.Stats(rate()))
                        }
                        NativeStep.DONE -> break
                        NativeStep.LENGTH_LIMIT -> {
                            emit(GenerationEvent.Stats(rate()))
                            emit(GenerationEvent.LengthLimitReached)
                            emit(GenerationEvent.Done)
                            return@withLock
                        }
                        else -> {
                            currentCoroutineContext().ensureActive()
                            throw LlamaException("The reply was stopped.")
                        }
                    }
                }
                emit(GenerationEvent.Stats(rate()))
                Log.i(TAG, "Reply: $generated tokens at ${String.format(java.util.Locale.ROOT, "%.1f", rate())} tok/s")
                emit(GenerationEvent.Done)
            } finally {
                activeReply = null
            }
        }
    }

    /**
     * Makes sure an image turn fits in memory and the image reader is loaded. Image turns need the
     * reader's weights (once) and the encoder's work buffers on top of the model; without the
     * check, Android would end the app in the middle of the turn.
     */
    private suspend fun prepareImages(loaded: Long) {
        val projector = projectorFile ?: throw LlamaException("$displayName cannot read images.")
        val needed = (if (projectorLoaded) 0L else projector.length()) + IMAGE_WORK_BYTES
        availableMemoryBytes()?.let { available ->
            if (available < needed) {
                throw LlamaException(
                    "There is not enough free memory to read this image right now (it needs about ${gigabytes(needed)}, " +
                        "${gigabytes(available.coerceAtLeast(0))} is free). Close other apps and try again, or send the message without the image.",
                )
            }
        }
        if (projectorLoaded) return
        withContext(backend.dispatcher) { backend.loadProjector(loaded, projector.absolutePath, config.threads) }
        projectorLoaded = true
    }

    private fun encode(turns: List<ChatTurn>, thinking: Boolean, withSizes: Boolean): NativePrompt {
        val roles = IntArray(turns.size) { index ->
            when (turns[index].role) {
                ChatRole.SYSTEM -> 0
                ChatRole.USER -> 1
                ChatRole.ASSISTANT -> 2
            }
        }
        val texts = Array(turns.size) { turns[it].text.encodeToByteArray() }
        val paths = Array(turns.size) { index ->
            turns[index].images.takeIf { it.isNotEmpty() }?.map { it.encodeToByteArray() }?.toTypedArray()
        }
        val sizes = Array(turns.size) { index ->
            if (!withSizes) return@Array null
            turns[index].images.takeIf { it.isNotEmpty() }?.flatMap { path ->
                val (width, height) = imageSize(path) ?: (DEFAULT_IMAGE_SIDE to DEFAULT_IMAGE_SIDE)
                listOf(width, height)
            }?.toIntArray()
        }
        return NativePrompt(roles, texts, paths, sizes, thinking)
    }

    private fun logPrompt(stats: PromptStats) {
        // Sizes and times only, never content.
        Log.i(
            TAG,
            "Prompt: ${stats.promptTokens} tokens (${stats.reusedTokens} reused), ${stats.images} image(s) in " +
                "${stats.imageMillis} ms, ${stats.totalMillis} ms in all",
        )
    }

    private companion object {
        const val TAG = "LlamaEngine"
        /** Assumed for counting when an image's size cannot be read; stored images are at most this. */
        const val DEFAULT_IMAGE_SIDE = 1024

        fun gigabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)
    }
}

/** An image file's width and height, read from its header only. */
private fun readImageSize(path: String): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/**
 * Runs blocking native [block] on [dispatcher]. If the caller is cancelled meanwhile, [onCancel]
 * runs at once (on the caller's side) so the native work can stop early; the call still waits for
 * [block] to return, so native state is never abandoned half-way.
 */
internal suspend fun <T> abortable(dispatcher: CoroutineDispatcher, onCancel: () -> Unit, block: () -> T): T =
    coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) onCancel()
            }
        }
        try {
            withContext(dispatcher) { block() }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
