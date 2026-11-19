package ai.unalome.pocket.llamaengine

import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ChatTurn
import ai.unalome.pocket.chatcore.GenerationEvent
import ai.unalome.pocket.chatcore.GenerationOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The engine's ordering and cancellation against a fake llama.cpp. */
class LlamaEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val executor = Executors.newSingleThreadExecutor()

    @After fun stop() = executor.shutdownNow().let { }

    private inner class FakeBackend : LlamaBackend {
        override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val aborted = AtomicBoolean(false)
        /** When set, `start` waits here, as a long prompt would. */
        var startGate: CountDownLatch? = null
        val startEntered = CompletableDeferred<Unit>()
        var lastPrompt: NativePrompt? = null
        var lastTemperature = 0f
        var steps = ArrayDeque(listOf(NativeStep(NativeStep.CONTINUE, null, "Hi".toByteArray()), NativeStep(NativeStep.DONE, null, null)))

        override fun ensureInitialised(nativeLibraryDir: String) {}
        override fun load(path: String, contextTokens: Int, threads: Int, listener: LoadListener?): Long = 1L
        override fun loadProjector(handle: Long, path: String, threads: Int) { calls += "loadProjector" }
        override fun free(handle: Long) { calls += "free" }
        override fun contextSize(handle: Long) = 4096
        override fun supportsThinking(handle: Long) = false
        override fun abort(handle: Long) { calls += "abort"; aborted.set(true) }
        override fun resetAbort(handle: Long) { calls += "resetAbort"; aborted.set(false) }
        override fun countTokens(handle: Long, prompt: NativePrompt) = prompt.texts.sumOf { it.size } + prompt.imageCount * 256
        override fun start(handle: Long, prompt: NativePrompt, temperature: Float, maxTokens: Int, seed: Int): Int {
            calls += "start"
            lastPrompt = prompt
            lastTemperature = temperature
            startEntered.complete(Unit)
            if (aborted.get()) return -1
            val gate = startGate
            if (gate != null) {
                // Reads the prompt until stopped, like the native prefill loop checking its abort flag.
                while (!aborted.get()) if (gate.await(5, TimeUnit.MILLISECONDS)) break
                if (aborted.get()) return -1
            }
            return 10
        }
        override fun lastPromptStats(handle: Long): PromptStats? = null
        override fun next(handle: Long): NativeStep = if (aborted.get()) NativeStep(NativeStep.ABORTED, null, null) else steps.removeFirst()
    }

    private fun engine(backend: FakeBackend, projector: Boolean = false, freeMemory: Long? = null): LlamaEngine {
        val model = folder.newFile("model.gguf").apply { writeText("x") }
        val projectorFile = if (projector) folder.newFile("mmproj.gguf").apply { writeBytes(ByteArray(1000)) } else null
        return LlamaEngine(
            model, "Test model", LlamaConfig(4096, 2), false, "/lib", projectorFile,
            availableMemoryBytes = { freeMemory }, imageSize = { 1024 to 768 }, backend = backend,
        )
    }

    private val hello = ChatTurn(ChatRole.USER, "Hello")

    @Test
    fun repliesWithTheTemperatureAndClearsTheAbortFlagBeforeDispatch() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load {}
        val events = engine.reply(hello, listOf(ChatTurn(ChatRole.SYSTEM, "Be brief.")), GenerationOptions(temperature = 0.2)).toList()

        assertTrue(events.contains(GenerationEvent.Token("Hi")))
        assertEquals(GenerationEvent.Done, events.last())
        assertEquals(0.2f, backend.lastTemperature, 0.0001f)
        assertEquals(listOf(0, 1), backend.lastPrompt!!.roles.toList()) // the system turn goes first
        assertEquals(listOf("resetAbort", "start"), backend.calls.filter { it == "resetAbort" || it == "start" })
    }

    @Test
    fun aStopThatArrivesBeforeThePromptIsReadIsKept() = runBlocking {
        // The old bug: `start` cleared the abort flag itself, so a Stop between dispatch and
        // `start` was lost and the whole prompt was read. Now `start` sees the flag and returns -1.
        val backend = FakeBackend()
        // A Stop that lands after the engine cleared the flag and before `start` runs.
        val stopped = object : LlamaBackend by backend {
            override fun resetAbort(handle: Long) {
                backend.resetAbort(handle)
                backend.abort(handle)
            }
        }
        val engineWithPendingStop = LlamaEngine(
            folder.newFile("m2.gguf").apply { writeText("x") }, "Test", LlamaConfig(4096, 2), false, "/lib", null,
            availableMemoryBytes = { null }, imageSize = { null }, backend = stopped,
        )
        engineWithPendingStop.load {}
        try {
            engineWithPendingStop.reply(hello, emptyList(), GenerationOptions()).toList()
            fail("expected the reply to stop")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(listOf("resetAbort", "abort", "start"), backend.calls.filter { it in setOf("resetAbort", "abort", "start") }.take(3))
    }

    @Test
    fun cancellingDuringALongPromptAbortsItAtOnce() = runBlocking {
        val backend = FakeBackend().apply { startGate = CountDownLatch(1) }
        val engine = engine(backend)
        engine.load {}
        val job = launch { engine.reply(hello, emptyList(), GenerationOptions()).toList() }
        withTimeout(2000) { backend.startEntered.await() }
        val started = System.nanoTime()
        job.cancel()
        withTimeout(2000) { job.join() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
        assertTrue(backend.calls.contains("abort"))
    }

    @Test
    fun imageTurnsOnATextOnlyModelAreRefusedClearly() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load {}
        assertFalse(engine.capabilities.supportsVision)
        val image = folder.newFile("photo.jpg").apply { writeBytes(ByteArray(10)) }
        try {
            engine.reply(ChatTurn(ChatRole.USER, "What is this?", listOf(image.absolutePath)), emptyList(), GenerationOptions()).toList()
            fail("expected a refusal")
        } catch (e: LlamaException) {
            assertTrue(e.message!!.startsWith("Test model cannot read images."))
        }
        assertFalse(backend.calls.contains("start"))
    }

    @Test
    fun imageTurnsCheckFreeMemoryBeforeLoadingTheImageReader() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend, projector = true, freeMemory = 100L shl 20)
        engine.load {}
        assertTrue(engine.capabilities.supportsVision)
        val image = folder.newFile("photo.jpg").apply { writeBytes(ByteArray(10)) }
        try {
            engine.reply(ChatTurn(ChatRole.USER, "What is this?", listOf(image.absolutePath)), emptyList(), GenerationOptions()).toList()
            fail("expected a memory refusal")
        } catch (e: LlamaException) {
            assertTrue(e.message!!.contains("not enough free memory"))
        }
        assertFalse(backend.calls.contains("loadProjector"))
        assertFalse(backend.calls.contains("start"))
    }

    @Test
    fun imageTurnsLoadTheImageReaderOnceAndPassThePaths() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend, projector = true, freeMemory = 4L shl 30)
        engine.load {}
        val image = folder.newFile("photo.jpg").apply { writeBytes(ByteArray(10)) }
        val turn = ChatTurn(ChatRole.USER, "What is this?", listOf(image.absolutePath))
        engine.reply(turn, emptyList(), GenerationOptions()).toList()
        backend.steps = ArrayDeque(listOf(NativeStep(NativeStep.DONE, null, null)))
        engine.reply(ChatTurn(ChatRole.USER, "And now?"), listOf(turn, ChatTurn(ChatRole.ASSISTANT, "A cat.")), GenerationOptions()).toList()

        assertEquals(1, backend.calls.count { it == "loadProjector" })
        val paths = backend.lastPrompt!!.imagePaths.map { it?.map(ByteArray::decodeToString) }
        assertEquals(listOf(listOf(image.absolutePath), null, null), paths)
    }
}
