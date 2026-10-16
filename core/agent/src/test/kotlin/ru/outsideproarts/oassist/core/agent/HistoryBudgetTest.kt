package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole

class HistoryBudgetTest {

    private fun voice(text: String) = ChatMessage(ChatRole.USER, text, audio = AudioPart("QUJDRA==", durationSec = 4))

    @Test
    fun keepsOnlyLastAudio() {
        val messages = listOf(
            voice("первый"),
            ChatMessage(ChatRole.ASSISTANT, "ок"),
            voice("второй"),
            ChatMessage(ChatRole.ASSISTANT, "готово"),
        )

        val trimmed = trimHistoryAudio(messages)

        assertNull(trimmed[0].audio)
        assertEquals("первый", trimmed[0].content)
        assertEquals("QUJDRA==", trimmed[2].audio?.wavBase64)
    }

    @Test
    fun strippedVoiceWithoutText_getsNote() {
        val trimmed = trimHistoryAudio(listOf(voice(""), ChatMessage(ChatRole.ASSISTANT, "ок"), voice("финал")))

        assertEquals(AUDIO_REMOVED_NOTE, trimmed[0].content)
        assertNull(trimmed[0].audio)
    }

    @Test
    fun singleVoice_keepsAudio() {
        val messages = listOf(voice("привет"))
        assertEquals(messages, trimHistoryAudio(messages))
    }

    @Test
    fun withoutAudio_untouched() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "а"),
            ChatMessage(ChatRole.ASSISTANT, "б"),
        )
        assertEquals(messages, trimHistoryAudio(messages))
    }
}
