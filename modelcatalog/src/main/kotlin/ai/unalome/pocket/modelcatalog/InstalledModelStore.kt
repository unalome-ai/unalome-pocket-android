package ai.unalome.pocket.modelcatalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A model whose verified file is on this device. */
@Serializable
data class InstalledModel(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val licenseName: String,
    val licenseUrl: String? = null,
    val licenseAcceptedAt: Long,
    val installedAt: Long,
    /** Decode speed measured during chats on this device, the latest. */
    val measuredTokensPerSecond: Double? = null,
)

/**
 * The installed-model records, the selected model and the licences the user accepted, kept in one
 * small JSON file next to the models. Written atomically (temporary file, then rename).
 */
class InstalledModelStore(private val file: File) {
    @Serializable
    data class Snapshot(
        val models: List<InstalledModel> = emptyList(),
        val selectedId: String? = null,
        /** Model id → when its licence was accepted (before the first download). */
        val acceptedLicenses: Map<String, Long> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun read(): Snapshot {
        if (!file.isFile) return Snapshot()
        return runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }.getOrElse { Snapshot() }
    }

    @Synchronized
    fun write(snapshot: Snapshot) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    @Synchronized
    fun update(change: (Snapshot) -> Snapshot): Snapshot = change(read()).also(::write)
}
