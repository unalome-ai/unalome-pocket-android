package ai.unalome.pocket.chatcore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Downscaling, EXIF orientation and clean-up, with real image decoding (Robolectric native graphics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun targetSizeKeepsTheAspectRatioAndNeverUpscales() {
        assertEquals(1024 to 768, AttachmentStore.targetSize(4032, 3024, 1024))
        assertEquals(768 to 1024, AttachmentStore.targetSize(3024, 4032, 1024))
        assertEquals(800 to 600, AttachmentStore.targetSize(800, 600, 1024))
        assertEquals(1024 to 1, AttachmentStore.targetSize(10_000, 3, 1024))
        assertEquals(2, AttachmentStore.sampleSize(4032, 3024, 1024))
        assertEquals(1, AttachmentStore.sampleSize(1500, 1000, 1024))
        assertEquals(4, AttachmentStore.sampleSize(4096, 100, 1024))
    }

    /** A landscape JPEG, left half red and right half blue, with an EXIF orientation tag. */
    private fun photo(width: Int, height: Int, orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val file = folder.newFile()
        file.writeBytes(jpeg)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_MAKE, "TestCamera")
            saveAttributes()
        }
        return file.readBytes()
    }

    @Test
    fun scalesTurnsUprightAndDropsMetadata() = runBlocking {
        val store = AttachmentStore(folder.newFolder("attachments"), maxSide = 200)
        val bytes = photo(400, 200, ExifInterface.ORIENTATION_ROTATE_90)

        val name = store.import { ByteArrayInputStream(bytes) }

        val saved = store.file(name)
        val bitmap = BitmapFactory.decodeFile(saved.absolutePath)
        // 400×200 rotated a quarter turn, then fitted into 200 px: 100 wide, 200 tall.
        assertEquals(100, bitmap.width)
        assertEquals(200, bitmap.height)
        // The left (red) half is now on top.
        assertTrue(Color.red(bitmap.getPixel(50, 20)) > 200 && Color.blue(bitmap.getPixel(50, 20)) < 60)
        assertTrue(Color.blue(bitmap.getPixel(50, 180)) > 200 && Color.red(bitmap.getPixel(50, 180)) < 60)
        val exif = ExifInterface(saved.absolutePath)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(listOf(saved.absolutePath), store.paths(listOf(name)))
    }

    @Test
    fun smallUprightImagesKeepTheirSize() = runBlocking {
        val store = AttachmentStore(folder.newFolder("attachments"), maxSide = 1024)
        val name = store.import { ByteArrayInputStream(photo(300, 200, ExifInterface.ORIENTATION_NORMAL)) }
        val bitmap = BitmapFactory.decodeFile(store.file(name).absolutePath)
        assertEquals(300, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun rejectsFilesThatAreNotImages() = runBlocking {
        val directory = folder.newFolder("attachments")
        val store = AttachmentStore(directory)
        try {
            store.import { ByteArrayInputStream("not an image".toByteArray()) }
            fail("expected an IOException")
        } catch (e: IOException) {
            // expected
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deletingAConversationOrMessageDeletesItsFiles() = runBlocking {
        val store = AttachmentStore(folder.newFolder("attachments"))
        val first = store.import { ByteArrayInputStream(photo(40, 20, ExifInterface.ORIENTATION_NORMAL)) }
        val second = store.import { ByteArrayInputStream(photo(40, 20, ExifInterface.ORIENTATION_NORMAL)) }
        val chats = ConversationStore(InMemoryConversationRepository(), attachments = store)

        val a = chats.createConversation()
        chats.appendMessage(a.id, ChatRole.USER, "What is this?", attachmentNames = listOf(first))
        val b = chats.createConversation()
        val message = chats.appendMessage(b.id, ChatRole.USER, "And this?", attachmentNames = listOf(second))

        chats.deleteMessages(listOf(message), keepAttachments = true)
        assertTrue(store.file(second).isFile)
        chats.deleteMessages(listOf(message))
        assertFalse(store.file(second).isFile)

        chats.delete(a.id)
        assertFalse(store.file(first).isFile)
        assertTrue(store.paths(listOf(first)).isEmpty())
    }

    @Test
    fun namesOutsideTheStoreAreRefused() {
        val store = AttachmentStore(File(folder.root, "attachments"))
        try {
            store.file("../models/x.gguf")
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        store.delete(listOf("../escape.jpg")) // ignored, no throw
    }
}
