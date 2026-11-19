package ai.unalome.pocket.llamaengine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** A native error, with a message written for the user. */
class LlamaException(message: String) : Exception(message)

/** Reports model load progress from 0 to 1; returning `false` stops the load. */
fun interface LoadListener {
    fun onProgress(progress: Float): Boolean
}

/** One generated token's worth of output. Built by the native code. */
class NativeStep(
    @JvmField val status: Int,
    @JvmField val reasoning: ByteArray?,
    @JvmField val content: ByteArray?,
) {
    companion object {
        const val CONTINUE = 0
        const val DONE = 1
        const val LENGTH_LIMIT = 2
        const val ABORTED = 3
    }
}

/**
 * A conversation as the native side takes it: one role, UTF-8 text and image list per turn.
 * [imagePaths] are read for a reply; [imageSizes] (width, height pairs) only count tokens.
 */
internal class NativePrompt(
    val roles: IntArray,
    val texts: Array<ByteArray>,
    val imagePaths: Array<Array<ByteArray>?>,
    val imageSizes: Array<IntArray?>,
    val thinking: Boolean,
) {
    val imageCount: Int get() = imagePaths.sumOf { it?.size ?: 0 }
}

/** What reading the latest prompt took. */
internal data class PromptStats(
    val promptTokens: Long,
    val reusedTokens: Long,
    val images: Long,
    val imageMillis: Long,
    val totalMillis: Long,
)

/**
 * The engine's view of llama.cpp. [NativeBackend] is the real one; tests substitute a fake to
 * check cancellation and ordering without the native library.
 */
internal interface LlamaBackend {
    /** Every call except [abort] and [resetAbort] runs here. */
    val dispatcher: CoroutineDispatcher
    fun ensureInitialised(nativeLibraryDir: String)
    fun load(path: String, contextTokens: Int, threads: Int, listener: LoadListener?): Long
    fun loadProjector(handle: Long, path: String, threads: Int)
    fun free(handle: Long)
    fun contextSize(handle: Long): Int
    fun supportsThinking(handle: Long): Boolean
    /** Thread-safe: stops a prompt being read or a reply being written, as soon as possible. */
    fun abort(handle: Long)
    /** Thread-safe: clears [abort], before a new reply is dispatched. */
    fun resetAbort(handle: Long)
    fun countTokens(handle: Long, prompt: NativePrompt): Int
    /** Reads the prompt; returns its token count, or -1 if aborted (also when aborted before it began). */
    fun start(handle: Long, prompt: NativePrompt, temperature: Float, maxTokens: Int, seed: Int): Int
    fun lastPromptStats(handle: Long): PromptStats?
    fun next(handle: Long): NativeStep
}

internal object NativeBackend : LlamaBackend {
    override val dispatcher: CoroutineDispatcher get() = LlamaNative.dispatcher
    override fun ensureInitialised(nativeLibraryDir: String) = LlamaNative.ensureInitialised(nativeLibraryDir)
    override fun load(path: String, contextTokens: Int, threads: Int, listener: LoadListener?) =
        LlamaNative.load(path.encodeToByteArray(), contextTokens, threads, listener)
    override fun loadProjector(handle: Long, path: String, threads: Int) =
        LlamaNative.loadProjector(handle, path.encodeToByteArray(), threads)
    override fun free(handle: Long) = LlamaNative.free(handle)
    override fun contextSize(handle: Long) = LlamaNative.contextSize(handle)
    override fun supportsThinking(handle: Long) = LlamaNative.supportsThinking(handle)
    override fun abort(handle: Long) = LlamaNative.abort(handle)
    override fun resetAbort(handle: Long) = LlamaNative.resetAbort(handle)
    override fun countTokens(handle: Long, prompt: NativePrompt) =
        LlamaNative.countTokens(handle, prompt.roles, prompt.texts, prompt.imageSizes, prompt.thinking)
    override fun start(handle: Long, prompt: NativePrompt, temperature: Float, maxTokens: Int, seed: Int) =
        LlamaNative.start(handle, prompt.roles, prompt.texts, prompt.imagePaths, prompt.thinking, temperature, maxTokens, seed)
    override fun lastPromptStats(handle: Long): PromptStats? =
        LlamaNative.lastPromptStats(handle)?.takeIf { it.size >= 5 }?.let { PromptStats(it[0], it[1], it[2], it[3], it[4]) }
    override fun next(handle: Long) = LlamaNative.next(handle)
}

/**
 * The JNI surface. Every call except [abort] and [resetAbort] must run on [dispatcher]: one thread
 * owns all native state, so loads, replies and unloads never overlap. Its 16 MB stack leaves room
 * for the Jinja template renderer's recursion.
 */
internal object LlamaNative {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "llama", 16L shl 20).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var initialised = false

    /** Loads the library and the ggml CPU backend found in [nativeLibraryDir]. */
    @Synchronized
    fun ensureInitialised(nativeLibraryDir: String) {
        if (initialised) return
        System.loadLibrary("unalome_llama")
        init(nativeLibraryDir)
        initialised = true
    }

    @JvmStatic external fun init(nativeLibraryDir: String)

    /** Returns a handle to free with [free]. Throws [LlamaException]. */
    @JvmStatic external fun load(path: ByteArray, contextTokens: Int, threads: Int, listener: LoadListener?): Long

    /** Loads the image reader (mmproj) for a loaded model. Throws [LlamaException]. */
    @JvmStatic external fun loadProjector(handle: Long, path: ByteArray, threads: Int)

    @JvmStatic external fun free(handle: Long)

    @JvmStatic external fun contextSize(handle: Long): Int

    @JvmStatic external fun supportsThinking(handle: Long): Boolean

    @JvmStatic external fun abort(handle: Long)

    @JvmStatic external fun resetAbort(handle: Long)

    @JvmStatic external fun countTokens(
        handle: Long,
        roles: IntArray,
        texts: Array<ByteArray>,
        imageSizes: Array<IntArray?>,
        thinking: Boolean,
    ): Int

    @JvmStatic external fun start(
        handle: Long,
        roles: IntArray,
        texts: Array<ByteArray>,
        imagePaths: Array<Array<ByteArray>?>,
        thinking: Boolean,
        temperature: Float,
        maxTokens: Int,
        seed: Int,
    ): Int

    @JvmStatic external fun lastPromptStats(handle: Long): LongArray?

    @JvmStatic external fun next(handle: Long): NativeStep
}
