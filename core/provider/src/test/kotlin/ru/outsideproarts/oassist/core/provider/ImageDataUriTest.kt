package ru.outsideproarts.oassist.core.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDataUriTest {

    @Test
    fun dataUri_wrapsBase64WithMime() {
        val part = ImagePart(imageBase64 = "QUJDRA==", mimeType = "image/jpeg")

        assertEquals("data:image/jpeg;base64,QUJDRA==", ImageCodec.dataUri(part))
    }

    @Test
    fun dataUri_respectsCustomMime() {
        val part = ImagePart(imageBase64 = "AA==", mimeType = "image/png")

        assertEquals("data:image/png;base64,AA==", ImageCodec.dataUri(part))
    }
}