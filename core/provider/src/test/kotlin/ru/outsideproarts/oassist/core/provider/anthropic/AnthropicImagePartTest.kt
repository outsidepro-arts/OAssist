package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ImagePart

class AnthropicImagePartTest {

    private val img = ImagePart(imageBase64 = "QUJDRA==", mimeType = "image/jpeg", width = 2, height = 2)

    @Test
    fun userMessage_withImages_mapsContentBlocks() {
        val (_, messages) = mapAnthropicMessages(
            listOf(ChatMessage(ChatRole.USER, "hello", images = listOf(img))),
        )

        val blocks = messages.single().content as JsonArray
        assertEquals(2, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        val image = blocks[1].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        assertEquals("base64", image["source"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("image/jpeg", image["source"]?.jsonObject?.get("media_type")?.jsonPrimitive?.content)
        assertEquals("QUJDRA==", image["source"]?.jsonObject?.get("data")?.jsonPrimitive?.content)
    }

    @Test
    fun userMessage_withoutImages_staysString() {
        val (_, messages) = mapAnthropicMessages(listOf(ChatMessage(ChatRole.USER, "plain")))

        assertEquals(JsonPrimitive("plain"), messages.single().content)
    }

    @Test
    fun toolResult_withImages_contentBlocks() {
        val (_, messages) = mapAnthropicMessages(
            listOf(ChatMessage(ChatRole.TOOL, "output", toolCallId = "call_1", images = listOf(img))),
        )

        val toolResult = (messages.single().content as JsonArray).single().jsonObject
        assertEquals("tool_result", toolResult["type"]?.jsonPrimitive?.content)
        val blocks = toolResult["content"] as JsonArray
        assertEquals(2, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun toolResult_withoutImages_staysStringContent() {
        val (_, messages) = mapAnthropicMessages(
            listOf(ChatMessage(ChatRole.TOOL, "output", toolCallId = "call_1")),
        )

        val toolResult = (messages.single().content as JsonArray).single().jsonObject
        assertEquals("output", toolResult["content"]?.jsonPrimitive?.content)
        assertTrue(toolResult["content"] is JsonPrimitive)
    }
}