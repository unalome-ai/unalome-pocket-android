package ai.unalome.pocket.modelcatalog

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

/** A model with an image reader: two files, projector first, the model moved into place last. */
class MultiFileDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val model = Random(1).nextBytes(200_000)
    private val projector = Random(2).nextBytes(80_000)
    private var served = mapOf("model.gguf" to model, "mmproj.gguf" to projector)
    private val requests = mutableListOf<RecordedRequest>()
    private lateinit var storage: ModelStorage

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val entry = CatalogEntry(
        id = "vision", displayName = "Vision", repo = "o/r", file = "model.gguf", revision = "b".repeat(40),
        downloadSizeBytes = model.size.toLong(), sha256 = sha(model),
        projector = CatalogEntry.ModelFile("mmproj.gguf", projector.size.toLong(), sha(projector)),
        minimumMemoryBytes = 1, recommendedMemoryBytes = 1, supportsVision = true,
        license = CatalogEntry.License("MIT"), goodFor = "tests", qualityRank = 1,
    )

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val name = request.path!!.substringAfterLast('/')
                val bytes = served[name] ?: return MockResponse().setResponseCode(404)
                val from = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val response = MockResponse().setBody(Buffer().write(bytes, from, bytes.size - from))
                return if (from > 0) response.setResponseCode(206).setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}") else response
            }
        }
        server.start()
        storage = ModelStorage(folder.newFolder("models"))
    }

    @After fun tearDown() = server.shutdown()

    private fun downloader() = ModelDownloader(
        storage = storage,
        freeSpace = { 10L shl 30 },
        urlFor = { server.url("/o/r/resolve/${it.revision}/${it.file}").toUrl() },
        isAllowedHost = { it == server.hostName },
        requireHttps = false,
        headroomBytes = 1000,
    )

    @Test
    fun downloadsBothFilesProjectorFirstWithOneProgressBar() = runBlocking {
        val updates = mutableListOf<DownloadProgress>()
        downloader().download(entry) { updates += it }

        assertArrayEquals(projector, storage.projectorFile(entry)!!.readBytes())
        assertArrayEquals(model, storage.modelFile(entry).readBytes())
        assertTrue(storage.isComplete(entry))
        assertEquals(listOf("mmproj.gguf", "model.gguf"), requests.map { it.path!!.substringAfterLast('/') })
        val total = (model.size + projector.size).toLong()
        assertTrue(updates.all { it.totalBytes == total })
        assertEquals(updates.map { it.completedBytes }, updates.map { it.completedBytes }.sorted())
        assertEquals(DownloadProgress(total, total), updates.last())
        assertEquals(0, storage.partialBytes(entry))
        assertEquals(total, storage.installedBytes(entry))
    }

    @Test
    fun installStateFollowsTheModelFileAndOffersAMissingProjector() {
        storage.prepare(entry)
        assertFalse(storage.isInstalled(entry))
        assertEquals(entry.totalDownloadBytes, storage.downloadTotalBytes(entry))

        // A finished projector alone is still an unfinished download.
        storage.projectorFile(entry)!!.writeBytes(projector)
        assertFalse(storage.isInstalled(entry))
        assertEquals(projector.size.toLong(), storage.partialBytes(entry))

        // A model installed before the catalog listed its projector: usable for text, projector offered.
        storage.projectorFile(entry)!!.delete()
        storage.modelFile(entry).writeBytes(model)
        assertTrue(storage.isInstalled(entry))
        assertFalse(storage.isComplete(entry))
        assertTrue(storage.needsProjector(entry))
        assertEquals(listOf(entry.projector), storage.downloadFiles(entry))
        assertEquals(projector.size.toLong(), storage.downloadTotalBytes(entry))

        // Dropping that projector download keeps the installed model.
        storage.partFile(entry, "mmproj.gguf").writeBytes(projector.copyOf(10))
        storage.deletePartial(entry)
        assertTrue(storage.modelFile(entry).isFile)
        assertFalse(storage.partFile(entry, "mmproj.gguf").exists())
    }

    @Test
    fun resumesTheSecondFileAfterTheFirstFinished() = runBlocking {
        storage.prepare(entry)
        storage.projectorFile(entry)!!.writeBytes(projector)
        storage.partFile(entry, "model.gguf").writeBytes(model.copyOf(50_000))
        val updates = mutableListOf<DownloadProgress>()

        downloader().download(entry) { updates += it }

        assertEquals(1, requests.size)
        assertEquals("model.gguf", requests.single().path!!.substringAfterLast('/'))
        assertEquals("bytes=50000-", requests.single().getHeader("Range"))
        assertArrayEquals(model, storage.modelFile(entry).readBytes())
        assertTrue(updates.first().completedBytes >= projector.size + 50_000L)
    }

    @Test
    fun addsOnlyTheProjectorToAnInstalledModel() = runBlocking {
        storage.prepare(entry)
        storage.modelFile(entry).writeBytes(model)
        val updates = mutableListOf<DownloadProgress>()

        downloader().download(entry) { updates += it }

        assertEquals(listOf("mmproj.gguf"), requests.map { it.path!!.substringAfterLast('/') })
        assertTrue(storage.hasProjector(entry))
        assertEquals(DownloadProgress(projector.size.toLong(), projector.size.toLong()), updates.last())
    }

    @Test
    fun aDamagedProjectorIsDeletedAndTheModelIsNotInstalled() = runBlocking {
        served = mapOf("model.gguf" to model, "mmproj.gguf" to projector.copyOf().also { it[5] = (it[5] + 1).toByte() })
        try {
            downloader().download(entry) {}
            fail("expected a checksum mismatch")
        } catch (e: DownloadException.ChecksumMismatch) {
            // expected
        }
        assertFalse(storage.partFile(entry, "mmproj.gguf").exists())
        assertFalse(storage.projectorFile(entry)!!.exists())
        assertFalse(storage.isInstalled(entry))
        assertEquals(listOf("mmproj.gguf"), requests.map { it.path!!.substringAfterLast('/') })
    }

    @Test
    fun catalogRequiresAProjectorExactlyForVision() {
        val base = """{"id":"x","displayName":"X","repo":"o/r","file":"m.gguf","revision":"${"c".repeat(40)}",
            "downloadSizeBytes":10,"minimumMemoryBytes":1,"recommendedMemoryBytes":2,
            "license":{"name":"MIT"},"goodFor":"t","qualityRank":1"""
        val ok = Catalog.parse("""{"version":1,"models":[$base,"supportsVision":true,"projector":{"file":"p.gguf","sizeBytes":5}}]}""")
        assertEquals(15, ok.models.single().totalDownloadBytes)
        assertEquals(listOf("p.gguf", "m.gguf"), ok.models.single().files.map { it.file })
        for (bad in listOf(
            """$base,"supportsVision":true}""",
            """$base,"projector":{"file":"p.gguf","sizeBytes":5}}""",
            """$base,"supportsVision":true,"projector":{"file":"m.gguf","sizeBytes":5}}""",
            """$base,"supportsVision":true,"projector":{"file":"../p.gguf","sizeBytes":5}}""",
        )) {
            try {
                Catalog.parse("""{"version":1,"models":[$bad]}""")
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
