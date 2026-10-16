package ru.outsideproarts.oassist.core.provider.anthropic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderError

class AnthropicAudioPartTest {

    @Test
    fun voiceMessage_failClosed_notSupported() {
        try {
            mapAnthropicMessages(
                listOf(ChatMessage(ChatRole.USER, "", audio = AudioPart("QUJDRA==", durationSec = 2))),
            )
            fail("ожидался ProviderError.NotSupported")
        } catch (e: ProviderError.NotSupported) {
            assertTrue(e.kind.contains("audio"))
        }
    }

    @Test
    fun textMessage_mapsAsBefore() {
        val (_, messages) = mapAnthropicMessages(listOf(ChatMessage(ChatRole.USER, "привет")))

        assertEquals(1, messages.size)
        assertEquals("user", messages.single().role)
    }
}
