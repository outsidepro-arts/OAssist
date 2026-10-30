package ru.outsideproarts.oassist.core.data.chat

import java.io.File
import java.io.IOException
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ImagePart

class ChatMediaStoreTest {

    private lateinit var root: File
    private lateinit var store: ChatMediaStore

    @Before
    fun setUp() {
        root = File.createTempFile("oassist-media", null).let { it.delete(); it.mkdirs(); it }
        store = ChatMediaStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun audio_roundTrip() {
        val ref = store.writeAudio("c1", "t1", AudioPart(wavBase64 = base64("WAVDATA"), durationSec = 3))!!
        assertEquals("c1/t1_a.wav", ref.path)
        assertEquals(3, ref.durationSec)

        val restored = store.readAudio(ref)!!
        assertEquals(base64("WAVDATA"), restored.wavBase64)
        assertEquals("audio/wav", restored.mimeType)
        assertEquals(3, restored.durationSec)
    }

    @Test
    fun image_roundTripKeepsMeta() {
        val ref = store.writeImage("c1", "t1", "u0", ImagePart(imageBase64 = base64("JPEG"), width = 800, height = 600))!!
        val restored = store.readImage(ref)!!
        assertEquals(base64("JPEG"), restored.imageBase64)
        assertEquals(800, restored.width)
        assertEquals(600, restored.height)
    }

    @Test
    fun invalidBase64_failsSoftly() {
        assertNull(store.writeImage("c1", "t1", "u0", ImagePart(imageBase64 = "не base64 !!!")))
    }

    @Test
    fun missingFile_readsNull() {
        assertNull(store.readImage(MediaRef(path = "c1/absent.jpg", mimeType = "image/jpeg")))
    }

    @Test
    fun deleteChat_removesOnlyItsFolder() {
        store.writeImage("c1", "t1", "u0", ImagePart(imageBase64 = base64("A")))
        store.writeImage("c2", "t1", "u0", ImagePart(imageBase64 = base64("B")))

        store.deleteChat("c1")

        assertFalse(File(root, "c1").exists())
        assertTrue(File(root, "c2").exists())
    }

    @Test
    fun deleteAll_removesRoot() {
        store.writeImage("c1", "t1", "u0", ImagePart(imageBase64 = base64("A")))
        store.deleteAll()
        assertFalse(File(root, "c1").exists())
    }

    @Test
    fun deleteAll_reportsFailedDeletion() {
        val undeletableRoot = object : File(root.absolutePath) {
            override fun delete() = false
        }
        val failure = runCatching { ChatMediaStore(undeletableRoot).deleteAll() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(root.exists())
    }

    @Test
    fun deleteTurn_reportsUnreadableChatDirectory() {
        File(root, "c1").writeText("not a directory")
        val failure = runCatching { store.deleteTurn("c1", "t1") }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun pathTraversal_isRejected() {
        File(root.parentFile, "secret.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.readImage(MediaRef(path = "../secret.bin", mimeType = "image/jpeg")))
    }

    @Test
    fun extensionFollowsMimeType() {
        val ref = store.writeImage("c1", "t1", "u0", ImagePart(imageBase64 = base64("A"), mimeType = "image/png"))!!
        assertTrue(ref.path.endsWith(".png"))
        assertNotNull(store.readImage(ref))
    }
}
