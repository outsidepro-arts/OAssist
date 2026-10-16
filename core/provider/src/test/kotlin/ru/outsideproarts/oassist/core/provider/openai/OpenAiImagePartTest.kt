package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.provider.ProviderJson

class OpenAiImagePartTest {

    private val img = ImagePart(imageBase64 = "QUJDRA==", mimeType = "image/jpeg", width = 2, height = 2)

    @Test
    fun userMessage_serializesImageUrlPart() {
        val dto = with(OpenAiCompatibleClient) {
            ChatMessage(ChatRole.USER, "", images = listOf(img)).toDto()
        }

        val json = ProviderJson.encodeToString(ChatMessageDto.serializer(), dto)

        assertTrue(json.contains("\"type\":\"image_url\""))
        assertTrue(json.contains("data:image/jpeg;base64,QUJDRA=="))
    }

    @Test
    fun userMessage_withText_keepsTextPartFirst() {
        val dto = with(OpenAiCompatibleClient) {
            ChatMessage(ChatRole.USER, "hello", images = listOf(img)).toDto()
        }

        val json = ProviderJson.encodeToString(ChatMessageDto.serializer(), dto)

        assertTrue(json.contains("\"type\":\"text\""))
        assertTrue(json.contains("hello"))
        assertTrue(json.indexOf("\"type\":\"text\"") < json.indexOf("\"type\":\"image_url\""))
    }

    @Test
    fun toolMessage_withImages_splitsIntoToolAndUser() {
        val msg = ChatMessage(ChatRole.TOOL, "output", toolCallId = "call_1", images = listOf(img))

        val dtos = with(OpenAiCompatibleClient) { msg.toDtos() }

        assertEquals(2, dtos.size)
        assertEquals("tool", dtos[0].role)
        assertEquals(JsonPrimitive("output"), dtos[0].content)
        assertEquals("user", dtos[1].role)
        val json = ProviderJson.encodeToString(ChatMessageDto.serializer(), dtos[1])
        assertTrue(json.contains("\"type\":\"image_url\""))
    }

    @Test
    fun toolMessage_withoutImages_staysSingleDto() {
        val msg = ChatMessage(ChatRole.TOOL, "output", toolCallId = "call_1")

        val dtos = with(OpenAiCompatibleClient) { msg.toDtos() }

        assertEquals(1, dtos.size)
        assertEquals("tool", dtos.single().role)
    }

    @Test
    fun textMessage_staysString() {
        val dto = with(OpenAiCompatibleClient) {
            ChatMessage(ChatRole.USER, "plain").toDto()
        }

        assertEquals(JsonPrimitive("plain"), dto.content)
    }
}