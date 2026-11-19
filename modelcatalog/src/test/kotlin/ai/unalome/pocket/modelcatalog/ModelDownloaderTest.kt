package ai.unalome.pocket.modelcatalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ModelDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val bytes = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private lateinit var storage: ModelStorage
    private val entry get() = CatalogEntry(
        id = "test-model", displayName = "Test", repo = "o/r", file = "test.gguf", revision = "a".repeat(40),
        downloadSizeBytes = bytes.size.toLong(), sha256 = sha, minimumMemoryBytes = 1, recommendedMemoryBytes = 1,
        license = CatalogEntry.License("MIT"), goodFor = "tests", qualityRank = 1,
    )

    @Before fun setUp() {
        server.start()
        storage = ModelStorage(folder.newFolder("models"))
    }

    @After fun tearDown() = server.shutdown()

    private fun downloader(free: Long? = 10L shl 30, allowed: (String) -> Boolean = { it == server.hostName }) = ModelDownloader(
        storage = storage,
        freeSpace = { free },
        urlFor = { server.url("/o/r/resolve/${it.revision}/${it.file}").toUrl() },
        isAllowedHost = allowed,
        requireHttps = false, // MockWebServer speaks plain HTTP; production keeps HTTPS required
        headroomBytes = 1000,
    )

    private fun body(from: Int = 0) = Buffer().write(bytes, from, bytes.size - from)

    @Test
    fun downloadsVerifiesAndRenamesIntoPlace() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val updates = mutableListOf<DownloadProgress>()
        val file = downloader().download(entry) { updates += it }

        assertEquals(storage.modelFile(entry), file)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(storage.partFile(entry).exists())
        assertTrue(updates.any { it.isVerifying })
        assertEquals(DownloadProgress(bytes.size.toLong(), bytes.size.toLong()), updates.last())

        val request = server.takeRequest()
        assertEquals("/o/r/resolve/${"a".repeat(40)}/test.gguf", request.path)
        assertEquals(ModelDownloader.USER_AGENT, request.getHeader("User-Agent"))
        assertNull(request.getHeader("Range"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun resumesFromThePartFileWithRange() = runBlocking {
        storage.prepare(entry)
        storage.partFile(entry).writeBytes(bytes.copyOf(120_000))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 120000-${bytes.size - 1}/${bytes.size}")
                .setBody(body(120_000)),
        )
        val file = downloader().download(entry) {}

        assertEquals("bytes=120000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresTheRange() = runBlocking {
        storage.prepare(entry)
        storage.partFile(entry).writeBytes(ByteArray(50_000) { 1 }) // wrong bytes: the full body must replace them
        server.enqueue(MockResponse().setBody(body()))
        assertArrayEquals(bytes, downloader().download(entry) {}.readBytes())
    }

    @Test
    fun followsRedirectsToAllowedHostsOnly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/cdn/file").toString()))
        server.enqueue(MockResponse().setBody(body()))
        assertArrayEquals(bytes, downloader().download(entry) {}.readBytes())
        server.takeRequest()
        assertEquals("/cdn/file", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://tracker.example.com/file"))
        try {
            storage.delete(entry)
            downloader().download(entry) {}
            fail("expected DisallowedHost")
        } catch (e: DownloadException.DisallowedHost) {
            assertEquals("tracker.example.com", e.host)
        }
    }

    @Test
    fun checksumMismatchDeletesTheFile() = runBlocking {
        val damaged = bytes.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(damaged)))
        try {
            downloader().download(entry) {}
            fail("expected ChecksumMismatch")
        } catch (_: DownloadException.ChecksumMismatch) {
        }
        assertFalse(storage.partFile(entry).exists())
        assertFalse(storage.isInstalled(entry))
    }

    @Test
    fun refusesWithoutEnoughFreeSpaceBeforeAnyRequest() = runBlocking {
        try {
            downloader(free = 200_000).download(entry) {}
            fail("expected NotEnoughSpace")
        } catch (e: DownloadException.NotEnoughSpace) {
            assertEquals(bytes.size + 1000L, e.needed)
            assertEquals(200_000L, e.available)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun partialBytesCountTowardsTheSpaceCheck() = runBlocking {
        storage.prepare(entry)
        storage.partFile(entry).writeBytes(bytes.copyOf(250_000))
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 250000-299999/300000").setBody(body(250_000)))
        assertTrue(downloader(free = 60_000).download(entry) {}.isFile)
    }

    @Test
    fun httpErrorsAreReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            downloader().download(entry) {}
            fail("expected BadStatus")
        } catch (e: DownloadException.BadStatus) {
            assertEquals(404, e.status)
            assertEquals("This model file is no longer on Hugging Face.", PlainErrorMessage.describe(e))
        }
    }

    @Test
    fun cancelKeepsThePartFileAndTheNextDownloadResumes() = runBlocking {
        server.enqueue(MockResponse().setBody(body()).throttleBody(16_384, 50, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            downloader().download(entry) { if (it.completedBytes > 0) started.complete(Unit) }
        }
        withTimeout(10_000) { started.await() }
        withTimeout(5_000) { job.cancelAndJoin() } // returns promptly: the stalled socket is closed
        assertTrue(job.isCancelled)
        val kept = storage.partialBytes(entry)
        assertTrue("kept $kept bytes", kept in 1 until bytes.size)
        assertFalse(storage.isInstalled(entry))

        server.takeRequest()
        server.enqueue(
            MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes $kept-${bytes.size - 1}/${bytes.size}")
                .setBody(body(kept.toInt())),
        )
        assertArrayEquals(bytes, downloader().download(entry) {}.readBytes())
        assertEquals("bytes=$kept-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun hostAllowList() {
        assertTrue(HuggingFace.isAllowedHost("huggingface.co"))
        assertTrue(HuggingFace.isAllowedHost("cdn-lfs.huggingface.co"))
        assertTrue(HuggingFace.isAllowedHost("us.aws.cdn.hf.co"))
        assertFalse(HuggingFace.isAllowedHost("huggingface.co.example.com"))
        assertFalse(HuggingFace.isAllowedHost("nothf.co"))
    }
}
