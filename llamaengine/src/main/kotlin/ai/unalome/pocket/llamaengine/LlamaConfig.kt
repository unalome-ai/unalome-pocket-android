package ai.unalome.pocket.llamaengine

import java.io.File

/** Where inference runs. CPU only for now; a GPU backend (OpenCL/Vulkan) is the next step. */
enum class Backend { CPU }

data class LlamaConfig(
    /** Context size asked for; the model's trained context caps it. */
    val contextTokens: Int,
    val threads: Int,
    val backend: Backend = Backend.CPU,
) {
    companion object {
        /** The configuration for this device. */
        fun forDevice(totalMemoryBytes: Long): LlamaConfig = LlamaConfig(
            contextTokens = DeviceTuning.contextTokens(totalMemoryBytes),
            threads = DeviceTuning.threads(DeviceTuning.readMaxFrequencies(), Runtime.getRuntime().availableProcessors()),
        )
    }
}

/** Device heuristics. Pure functions plus one reader, so the rules are tested. */
object DeviceTuning {
    private const val GIB = 1L shl 30

    /** 4096 tokens by default; 8192 on phones with 12 GB or more. */
    fun contextTokens(totalMemoryBytes: Long): Int = if (totalMemoryBytes >= 11 * GIB) 8192 else 4096

    /**
     * Threads for decoding: the performance cores, i.e. every core outside the slowest cluster
     * (phones mix 2–4 clusters of different speeds; running on the efficiency cores slows the
     * whole step down to their pace). With one cluster or unknown speeds, all cores but one (which
     * keeps the UI responsive), at most 4. Between 1 and 8.
     */
    fun threads(maxFrequencies: List<Long>, cores: Int): Int {
        val known = maxFrequencies.filter { it > 0 }
        val count = if (known.size >= 2 && known.distinct().size > 1) {
            val slowest = known.min()
            known.count { it > slowest }
        } else {
            (cores - 1).coerceAtMost(4)
        }
        return count.coerceIn(1, 8)
    }

    /** Each core's maximum frequency in kHz, from sysfs; empty where it is not readable. */
    fun readMaxFrequencies(): List<Long> {
        val cpus = File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) } ?: return emptyList()
        return cpus.mapNotNull { cpu ->
            runCatching { File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
    }
}
