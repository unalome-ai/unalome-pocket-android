package ai.unalome.pocket.modelcatalog

/** The facts about this device that decide which models it can run. A plain value for tests. */
data class DeviceProfile(
    /** `ActivityManager.MemoryInfo.totalMem`. */
    val totalMemoryBytes: Long,
    /** Free space where models are stored; `null` if unknown. */
    val freeStorageBytes: Long?,
) {
    /** Total memory rounded up to the marketed size, in bytes. */
    val nominalMemoryBytes: Long get() = ByteFormat.nominalGigabytes(totalMemoryBytes).toLong() shl 30
}

sealed interface ModelLabel {
    /** The one model to pick on this device. */
    data object Recommended : ModelLabel
    data object Runs : ModelLabel
    data class TooLarge(val reason: String) : ModelLabel

    val isUsable: Boolean get() = this !is TooLarge
}

data class Recommendation(val labels: Map<String, ModelLabel>, val recommendedId: String?) {
    fun label(id: String): ModelLabel = labels[id] ?: ModelLabel.TooLarge("Unknown model.")
}

/** Decides, per catalog model, how well it suits a device. Pure: same inputs, same answer. */
object ModelRecommender {
    /** Free storage that must remain after a download. */
    const val STORAGE_HEADROOM_BYTES = 1L shl 30

    /**
     * Too large: the phone has less memory than the model's minimum, or too little free storage
     * (not counted for installed models, nor for bytes already downloaded).
     * Recommended: the best model whose recommended memory the phone has; if none, the usable
     * model needing the least memory.
     */
    fun recommend(
        catalog: List<CatalogEntry>,
        device: DeviceProfile,
        installedIds: Set<String> = emptySet(),
        partialBytes: Map<String, Long> = emptyMap(),
    ): Recommendation {
        val memory = device.nominalMemoryBytes
        val labels = catalog.associate { entry ->
            entry.id to fit(entry, device, memory, entry.id in installedIds, partialBytes[entry.id] ?: 0)
        }.toMutableMap()
        val usable = catalog.filter { labels[it.id]?.isUsable == true }
        val pick = usable.filter { it.recommendedMemoryBytes <= memory }.maxByOrNull { it.qualityRank }
            ?: usable.minByOrNull { it.minimumMemoryBytes }
        if (pick != null) labels[pick.id] = ModelLabel.Recommended
        return Recommendation(labels, pick?.id)
    }

    private fun fit(entry: CatalogEntry, device: DeviceProfile, memory: Long, installed: Boolean, partial: Long): ModelLabel {
        if (memory < entry.minimumMemoryBytes) {
            return ModelLabel.TooLarge(
                "Needs a phone with ${ByteFormat.memory(entry.minimumMemoryBytes)} of memory; this one has ${ByteFormat.memory(device.totalMemoryBytes)}.",
            )
        }
        val free = device.freeStorageBytes
        if (!installed && free != null) {
            val needed = (entry.totalDownloadBytes - partial).coerceAtLeast(0) + STORAGE_HEADROOM_BYTES
            if (needed > free) {
                return ModelLabel.TooLarge("Needs ${ByteFormat.file(needed)} of free storage; ${ByteFormat.file(free)} is free.")
            }
        }
        return ModelLabel.Runs
    }
}
