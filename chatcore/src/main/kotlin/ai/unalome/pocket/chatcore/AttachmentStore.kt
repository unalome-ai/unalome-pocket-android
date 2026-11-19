package ai.unalome.pocket.chatcore

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Deletes attachment files; what [ConversationStore] needs from [AttachmentStore]. */
fun interface AttachmentFiles {
    fun delete(names: List<String>)
}

/**
 * Images attached to messages, stored app-private as `<uuid>.jpg` in [directory].
 *
 * Each picked or photographed image is decoded, turned upright from its EXIF orientation, scaled
 * so its longest side is at most [maxSide] pixels and saved as a JPEG without any metadata (no
 * location, no camera details). Messages keep the file names; the files go when their message or
 * conversation is deleted.
 */
// The framework ExifInterface reads streams correctly from Android 7 on (minSdk is 29), so the
// AndroidX copy would only add a dependency.
@SuppressLint("ExifInterface")
class AttachmentStore(val directory: File, private val maxSide: Int = DEFAULT_MAX_SIDE) : AttachmentFiles {
    fun file(name: String): File {
        require(name.matches(NAME)) { "Not an attachment name: $name" }
        return File(directory, name)
    }

    /** Absolute paths for a message's attachments, skipping any whose file is gone. */
    fun paths(names: List<String>): List<String> = names.mapNotNull { name ->
        runCatching { file(name) }.getOrNull()?.takeIf(File::isFile)?.absolutePath
    }

    /**
     * Stores the image [open] reads (called more than once: for the size, the orientation and the
     * pixels). Returns the new file's name. Throws [IOException] if it is not a readable image.
     */
    suspend fun import(open: () -> InputStream): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file is not an image the app can read.")
        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("This file is not an image the app can read.")
        val (width, height) = targetSize(decoded.width, decoded.height, maxSide)
        val scaled = if (width != decoded.width || height != decoded.height) {
            Bitmap.createScaledBitmap(decoded, width, height, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val upright = orient(scaled, orientation)

        directory.mkdirs()
        val name = "${UUID.randomUUID()}.jpg"
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            temporary.outputStream().use { output ->
                if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) throw IOException("The image could not be saved.")
            }
            if (!temporary.renameTo(target)) throw IOException("The image could not be saved.")
        } finally {
            temporary.delete()
            upright.recycle()
        }
        name
    }

    override fun delete(names: List<String>) {
        names.forEach { name -> runCatching { file(name).delete() } }
    }

    companion object {
        /** Images are stored at most this size; enough for the image readers, small on disk. */
        const val DEFAULT_MAX_SIDE = 1024
        const val MAX_PER_MESSAGE = 4
        private const val JPEG_QUALITY = 85
        private val NAME = Regex("[0-9a-f-]{36}\\.jpg")

        /** The size that fits [width] × [height] within [maxSide], keeping the aspect ratio. Never upscales. */
        fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= maxSide) return width to height
            val scale = maxSide.toDouble() / longest
            return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
        }

        /** The largest power-of-two decode step that still leaves the image at least [maxSide] on its longest side. */
        fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
            return sample
        }

        /** [bitmap] turned upright for an EXIF [orientation]; the same bitmap when it already is. */
        fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                else -> return bitmap
            }
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (turned !== bitmap) bitmap.recycle()
            return turned
        }
    }
}
