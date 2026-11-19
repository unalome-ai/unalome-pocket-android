package ai.unalome.pocket.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    private val bundled = File("src/main/assets/catalog.json").readText()

    @Test
    fun bundledCatalogParsesAndIsComplete() {
        val catalog = Catalog.parse(bundled)
        assertEquals(1, catalog.version)
        assertEquals(7, catalog.models.size)
        for (entry in catalog.models) {
            assertTrue(entry.id, entry.sha256 != null)
            assertTrue(entry.id, entry.license.url != null)
            assertTrue(entry.id, entry.goodFor.isNotBlank())
            assertTrue(entry.id, entry.file.endsWith(".gguf"))
        }
        assertEquals(setOf(1, 2, 4, 5, 6, 7, 8), catalog.models.map { it.qualityRank }.toSet())
    }

    @Test
    fun entriesCarryTheVerifiedFacts() {
        val qwen = Catalog.parse(bundled).entry("qwen3.5-0.8b-q4_k_m")!!
        assertEquals("unsloth/Qwen3.5-0.8B-GGUF", qwen.repo)
        assertEquals("Qwen3.5-0.8B-Q4_K_M.gguf", qwen.file)
        assertEquals(532_517_120L, qwen.downloadSizeBytes)
        assertTrue(qwen.supportsThinking)
        assertEquals("Apache 2.0", qwen.license.name)
        assertEquals(
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf",
            HuggingFace.fileUrl(qwen).toString(),
        )
        assertEquals("Gemma Terms of Use", Catalog.parse(bundled).entry("gemma-3-1b-it-q4_k_m")!!.license.name)
    }

    @Test
    fun unknownFieldsAreIgnoredAndOptionalOnesDefault() {
        val catalog = Catalog.parse(
            """{"version":1,"future":true,"models":[{"id":"a","displayName":"A","repo":"o/r","file":"a.gguf",
              "revision":"${"a".repeat(40)}","downloadSizeBytes":10,"minimumMemoryBytes":1,"recommendedMemoryBytes":2,
              "license":{"name":"MIT"},"goodFor":"x","qualityRank":1,"extra":1}]}""",
        )
        val entry = catalog.models.single()
        assertNull(entry.sha256)
        assertEquals(false, entry.supportsThinking)
    }

    @Test
    fun rejectsUnsafeOrNewerCatalogs() {
        fun model(file: String = "a.gguf", revision: String = "a".repeat(40)) =
            """{"id":"a","displayName":"A","repo":"o/r","file":"$file","revision":"$revision","downloadSizeBytes":10,
               "minimumMemoryBytes":1,"recommendedMemoryBytes":2,"license":{"name":"MIT"},"goodFor":"x","qualityRank":1}"""
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse("""{"version":2,"models":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse("""{"version":1,"models":[${model(file = "../x.gguf")}]}""") }
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse("""{"version":1,"models":[${model(revision = "main")}]}""") }
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse("""{"version":1,"models":[${model()},${model()}]}""") }
    }

    @Test
    fun byteFormatting() {
        assertEquals("533 MB", ByteFormat.file(532_517_120))
        assertEquals("1.28 GB", ByteFormat.file(1_280_835_840))
        assertEquals("5.68 GB", ByteFormat.file(5_680_522_464))
        assertEquals("12.0 GB", ByteFormat.file(12_000_000_000))
        assertEquals("12 GB", ByteFormat.memory((11.2 * (1L shl 30)).toLong())) // what a 12 GB phone reports
        assertEquals("3 GB", ByteFormat.memory(2_592_632_832))   // the emulator: 2.4 GiB
    }

    @Test
    fun plainErrorMessages() {
        assertTrue(PlainErrorMessage.describe(java.net.UnknownHostException()).startsWith("No internet connection"))
        assertTrue(PlainErrorMessage.describe(DownloadException.BadStatus(404)).contains("no longer on Hugging Face"))
        assertTrue(PlainErrorMessage.describe(DownloadException.NotEnoughSpace(2_000_000_000, 1_000_000_000)).startsWith("Not enough free storage"))
        assertTrue(PlainErrorMessage.describe(java.io.IOException("write failed: ENOSPC (No space left on device)")).contains("ran out of storage"))
    }
}
