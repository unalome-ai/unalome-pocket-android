package ai.unalome.pocket.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstalledModelStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val model = InstalledModel(
        id = "qwen3.5-0.8b-q4_k_m", displayName = "Qwen 3.5 0.8B", sizeBytes = 532_517_120, licenseName = "Apache 2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0", licenseAcceptedAt = 1, installedAt = 2,
    )

    @Test
    fun emptyUntilWrittenThenSurvivesANewInstance() {
        val file = File(folder.root, "models/installed.json")
        assertEquals(InstalledModelStore.Snapshot(), InstalledModelStore(file).read())

        InstalledModelStore(file).update {
            it.copy(models = listOf(model), selectedId = model.id, acceptedLicenses = mapOf(model.id to 1L))
        }
        val reopened = InstalledModelStore(file).read()
        assertEquals(listOf(model), reopened.models)
        assertEquals(model.id, reopened.selectedId)
        assertEquals(mapOf(model.id to 1L), reopened.acceptedLicenses)
        assertFalse(File(file.parentFile, "installed.json.tmp").exists())
    }

    @Test
    fun updatesMeasurementsAndRemovals() {
        val store = InstalledModelStore(folder.newFile("installed.json"))
        store.write(InstalledModelStore.Snapshot(models = listOf(model), selectedId = model.id))
        store.update { s -> s.copy(models = s.models.map { it.copy(measuredTokensPerSecond = 21.5) }) }
        assertEquals(21.5, store.read().models.single().measuredTokensPerSecond!!, 0.0)
        store.update { it.copy(models = emptyList(), selectedId = null) }
        assertTrue(store.read().models.isEmpty())
    }

    @Test
    fun aDamagedFileReadsAsEmptyInsteadOfCrashing() {
        val file = folder.newFile("installed.json").apply { writeText("{not json") }
        assertEquals(InstalledModelStore.Snapshot(), InstalledModelStore(file).read())
    }

    @Test
    fun storageLayoutAndDeletion() {
        val storage = ModelStorage(folder.newFolder("models"))
        val entry = CatalogEntry(
            id = "m", displayName = "M", repo = "o/r", file = "m.gguf", revision = "a".repeat(40), downloadSizeBytes = 10,
            minimumMemoryBytes = 1, recommendedMemoryBytes = 1, license = CatalogEntry.License("MIT"), goodFor = "x", qualityRank = 1,
        )
        storage.prepare(entry)
        storage.modelFile(entry).writeBytes(ByteArray(10))
        storage.partFile(entry).writeBytes(ByteArray(4))
        java.io.File(storage.root, "installed.json").writeText("{}") // records, not a model: not counted
        assertTrue(storage.isInstalled(entry))
        assertEquals(4, storage.partialBytes(entry))
        assertEquals(14, storage.totalBytes())
        storage.delete(entry)
        assertFalse(storage.isInstalled(entry))
        assertEquals(0, storage.totalBytes())
    }
}
