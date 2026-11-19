package ai.unalome.pocket.modelcatalog

import java.util.Locale
import kotlin.math.ceil

/** Human-readable sizes, decimal like the iOS app: "533 MB", "1.28 GB". */
object ByteFormat {
    private const val GIB = 1L shl 30

    fun file(bytes: Long): String = when {
        bytes < 1_000_000 -> "${(bytes + 999) / 1000} KB"
        bytes < 1_000_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes < 10_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
        else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
    }

    /** Device memory as marketed: total RAM rounded up to whole GB ("8 GB"). */
    fun memory(bytes: Long): String = "${nominalGigabytes(bytes)} GB"

    /**
     * Android reports less RAM than the phone is sold with (the kernel and carve-outs keep some),
     * e.g. 11.2 GiB on a 12 GB phone. Rounding up recovers the marketed size.
     */
    fun nominalGigabytes(bytes: Long): Int = ceil(bytes.toDouble() / GIB).toInt()
}
