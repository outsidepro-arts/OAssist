package ru.outsideproarts.oassist.core.provider.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.AudioPart
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole

class GeminiAudioPartTest {

    @Test
    fun voiceMessage_appendsInlineDataBlob() {
        val (_, contents) = mapGeminiContents(
            listOf(ChatMessage(ChatRole.USER, "", audio = AudioPart("QUJDRA==", durationSec = 2))),
        )

        val parts = contents.single().parts
        assertEquals(1, parts.size)
        assertEquals("audio/wav", parts.single().inlineData?.mimeType)
        assertEquals("QUJDRA==", parts.single().inlineData?.data)
    }

    @Test
    fun voiceMessage_withText_keepsTextPartFirst() {
        val (_, contents) = mapGeminiContents(
            listOf(ChatMessage(ChatRole.USER, "слушай", audio = AudioPart("QUJDRA==", durationSec = 1))),
        )

        val parts = contents.single().parts
        assertEquals(2, parts.size)
        assertEquals("слушай", parts[0].text)
        assertTrue(parts[1].inlineData != null)
    }

    @Test
    fun textMessage_unchanged_singleTextPart() {
        val (_, contents) = mapGeminiContents(listOf(ChatMessage(ChatRole.USER, "привет")))

        val parts = contents.single().parts
        assertEquals(1, parts.size)
        assertEquals("привет", parts.single().text)
    }
}
