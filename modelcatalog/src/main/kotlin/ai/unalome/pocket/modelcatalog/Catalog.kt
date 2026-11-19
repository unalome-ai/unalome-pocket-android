package ai.unalome.pocket.modelcatalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One downloadable model: a GGUF file at a pinned Hugging Face revision and, for models that read
 * images, its projector (the mmproj GGUF) from the same repo and revision.
 */
@Serializable
data class CatalogEntry(
    /** Stable id used by the app (storage folder, preferences). */
    val id: String,
    val displayName: String,
    /** Hugging Face repo, e.g. `unsloth/Qwen3.5-0.8B-GGUF`. */
    val repo: String,
    /** The model's GGUF file in the repo. */
    val file: String,
    /** Pinned commit SHA; downloads always fetch this exact revision. */
    val revision: String,
    /** Size of [file]. */
    val downloadSizeBytes: Long,
    /** Lowercase hex SHA-256 of the file (Hugging Face's LFS oid); `null` skips the check. */
    val sha256: String? = null,
    /** The image reader, for models that read images. */
    val projector: ModelFile? = null,
    /** Total device memory (RAM) below which the model is too large. */
    val minimumMemoryBytes: Long,
    /** Total device memory from which the model is a comfortable default. */
    val recommendedMemoryBytes: Long,
    /** The model reads images; true exactly when it has a [projector]. */
    val supportsVision: Boolean = false,
    /** The chat template can switch reasoning on and off. */
    val supportsThinking: Boolean = false,
    val license: License,
    val goodFor: String,
    /** Editorial ordering by answer quality; higher is better. */
    val qualityRank: Int,
) {
    @Serializable
    data class License(val name: String, val url: String? = null)

    /** Another file of the same repo and revision. */
    @Serializable
    data class ModelFile(val file: String, val sizeBytes: Long, val sha256: String? = null)

    /** Every file of the model, in download order: the projector first, the model last. */
    val files: List<ModelFile>
        get() = listOfNotNull(projector, ModelFile(file, downloadSizeBytes, sha256))

    /** What a full download fetches: the model plus its projector. */
    val totalDownloadBytes: Long get() = files.sumOf { it.sizeBytes }
}

@Serializable
data class Catalog(val version: Int, val models: List<CatalogEntry>) {
    fun entry(id: String): CatalogEntry? = models.firstOrNull { it.id == id }

    companion object {
        const val SUPPORTED_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** Parses and checks a catalog file. Throws [IllegalArgumentException] on anything unusable. */
        fun parse(text: String): Catalog {
            val catalog = json.decodeFromString(serializer(), text)
            require(catalog.version <= SUPPORTED_VERSION) { "Catalog version ${catalog.version} is newer than this app." }
            require(catalog.models.map { it.id }.toSet().size == catalog.models.size) { "Duplicate model ids." }
            for (entry in catalog.models) {
                require(entry.revision.matches(Regex("[0-9a-f]{40}"))) { "${entry.id}: revision must be a commit SHA." }
                require(entry.minimumMemoryBytes <= entry.recommendedMemoryBytes) { "${entry.id}: memory values reversed." }
                require(entry.supportsVision == (entry.projector != null)) { "${entry.id}: vision needs a projector file, and only then." }
                require(entry.files.map { it.file }.toSet().size == entry.files.size) { "${entry.id}: duplicate file names." }
                for (file in entry.files) {
                    require(file.sha256 == null || file.sha256.matches(SHA256)) { "${entry.id}: bad SHA-256 for ${file.file}." }
                    require(file.sizeBytes > 0) { "${entry.id}: size missing for ${file.file}." }
                    require(file.file.endsWith(".gguf") && '/' !in file.file && ".." !in file.file) { "${entry.id}: bad file name ${file.file}." }
                }
            }
            return catalog
        }
    }
}
