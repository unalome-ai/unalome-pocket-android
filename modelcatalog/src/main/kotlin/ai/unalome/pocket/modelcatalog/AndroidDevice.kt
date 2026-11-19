package ai.unalome.pocket.modelcatalog

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/** Reads the [DeviceProfile] of the phone the app runs on. */
object AndroidDevice {
    fun totalMemoryBytes(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    fun freeStorageBytes(directory: File): Long? {
        var existing: File? = directory
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return existing?.let { runCatching { StatFs(it.path).availableBytes }.getOrNull() }
    }

    fun profile(context: Context, storageDirectory: File): DeviceProfile =
        DeviceProfile(totalMemoryBytes(context), freeStorageBytes(storageDirectory))
}
