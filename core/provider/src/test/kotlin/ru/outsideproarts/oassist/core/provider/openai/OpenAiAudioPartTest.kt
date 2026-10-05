package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.AudioPart
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderJson

class OpenAiAudioPartTest {

    @Test
    fun voiceMessage_serializesInputAudioPart() {
        val dto = with(OpenAiCompatibleClient) {
            ChatMessage(ChatRole.USER, "", audio = AudioPart("QUJDRA==", durationSec = 3)).toDto()
        }

        val json = ProviderJson.encodeToString(ChatMessageDto.serializer(), dto)

        assertTrue(json.contains("\"type\":\"input_audio\""))
        assertTrue(json.contains("\"data\":\"QUJDRA==\""))
        assertTrue(json.contains("\"format\":\"wav\""))
    }

    @Test
    fun voiceMessage_withText_keepsTextPart() {
        val dto = with(OpenAiCompatibleClient) {
            ChatMessage(ChatRole.USER, "слушай", audio = AudioPart("QUJDRA==", durationSec = 1)).toDto()
        }

        val json = ProviderJson.encodeToString(ChatMessageDto.serializer(), dto)

        assertTrue(json.contains("\"type\":\"text\""))
        assertTrue(json.contains("слушай"))
        assertTrue(json.contains("\"type\":\"input_audio\""))
    }

    @Test
    fun textMessage_staysString() {
        val dto = with(OpenAiCompatibleClient) {
            ChatMessage(ChatRole.USER, "привет").toDto()
        }

        assertEquals(JsonPrimitive("привет"), dto.content)
    }
}
