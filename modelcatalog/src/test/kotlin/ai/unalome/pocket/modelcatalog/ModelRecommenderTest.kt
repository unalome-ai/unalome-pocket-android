package ai.unalome.pocket.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelRecommenderTest {
    private val catalog = Catalog.parse(File("src/main/assets/catalog.json").readText()).models
    private val gib = 1L shl 30
    private val plenty = 100 * gib

    private fun recommend(totalMemory: Long, free: Long? = plenty, installed: Set<String> = emptySet(), partial: Map<String, Long> = emptyMap()) =
        ModelRecommender.recommend(catalog, DeviceProfile(totalMemory, free), installed, partial)

    private fun usable(r: Recommendation) = catalog.filter { r.label(it.id).isUsable }.map { it.id }.toSet()

    @Test
    fun smallEmulatorGetsOnlyTheSmallestQwen() {
        val r = recommend(2_592_632_832) // reports 2.4 GiB: a "3 GB" device
        assertEquals("qwen3.5-0.8b-q4_k_m", r.recommendedId)
        assertEquals(setOf("qwen3.5-0.8b-q4_k_m"), usable(r))
        val gemma = r.label("gemma-3-1b-it-q4_k_m") as ModelLabel.TooLarge
        assertEquals("Needs a phone with 4 GB of memory; this one has 3 GB.", gemma.reason)
    }

    @Test
    fun eightGigabytePhone() {
        val r = recommend((7.4 * gib).toLong())
        assertEquals("qwen3.5-2b-q4_k_m", r.recommendedId)
        assertEquals(
            setOf("qwen3.5-0.8b-q4_k_m", "gemma-3-1b-it-q4_k_m", "qwen3.5-2b-q4_k_m", "gemma-4-e2b-it-q4_0", "qwen3.5-4b-q4_k_m"),
            usable(r),
        )
        assertEquals(ModelLabel.Recommended, r.label("qwen3.5-2b-q4_k_m"))
        assertEquals(ModelLabel.Runs, r.label("qwen3.5-4b-q4_k_m"))
    }

    @Test
    fun twelveAndSixteenGigabytePhones() {
        val twelve = recommend((11.2 * gib).toLong())
        assertEquals("qwen3.5-4b-q4_k_m", twelve.recommendedId)
        assertTrue(twelve.label("gemma-4-e4b-it-q4_0").isUsable)
        assertTrue(twelve.label("qwen3.5-9b-q4_k_m") is ModelLabel.TooLarge) // 16 GB phones only

        val sixteen = recommend((15.3 * gib).toLong())
        assertEquals("gemma-4-e4b-it-q4_0", sixteen.recommendedId)
        assertTrue(sixteen.label("qwen3.5-9b-q4_k_m").isUsable)
    }

    @Test
    fun exactlyOneRecommendationPerDevice() {
        for (memory in listOf(2L, 3, 4, 6, 8, 12, 16, 24)) {
            val r = recommend(memory * gib)
            assertTrue("$memory GB", r.labels.values.count { it == ModelLabel.Recommended } <= 1)
            if (r.recommendedId != null) assertEquals(ModelLabel.Recommended, r.label(r.recommendedId!!))
        }
    }

    @Test
    fun storageDecidesTooLargeExceptForInstalledOrPartlyDownloadedModels() {
        val noRoom = recommend(8 * gib, free = 1_000_000_000)
        assertTrue(noRoom.label("qwen3.5-0.8b-q4_k_m") is ModelLabel.TooLarge)
        assertEquals(null, noRoom.recommendedId)
        assertTrue(recommend(8 * gib, free = 1_000_000_000, installed = setOf("qwen3.5-0.8b-q4_k_m")).label("qwen3.5-0.8b-q4_k_m").isUsable)
        // 533 MB + 1 GiB headroom does not fit in 1.4 GB; the remaining 33 MB + headroom does.
        assertTrue(recommend(8 * gib, free = 1_400_000_000).label("qwen3.5-0.8b-q4_k_m") is ModelLabel.TooLarge)
        val partial = recommend(8 * gib, free = 1_400_000_000, partial = mapOf("qwen3.5-0.8b-q4_k_m" to 500_000_000L))
        assertTrue(partial.label("qwen3.5-0.8b-q4_k_m").isUsable)
        assertTrue(recommend(8 * gib, free = null).label("qwen3.5-4b-q4_k_m").isUsable) // unknown storage is not held against a model
    }
}
