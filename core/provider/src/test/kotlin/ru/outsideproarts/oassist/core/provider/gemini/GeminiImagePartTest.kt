package ru.outsideproarts.oassist.core.provider.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ToolCallRef

class GeminiImagePartTest {

    private val img = ImagePart(imageBase64 = "QUJDRA==", mimeType = "image/jpeg", width = 2, height = 2)

    @Test
    fun userMessage_appendsInlineDataBlob() {
        val (_, contents) = mapGeminiContents(
            listOf(ChatMessage(ChatRole.USER, "", images = listOf(img))),
        )

        val parts = contents.single().parts
        assertEquals(1, parts.size)
        assertEquals("image/jpeg", parts.single().inlineData?.mimeType)
        assertEquals("QUJDRA==", parts.single().inlineData?.data)
    }

    @Test
    fun userMessage_withText_keepsTextPartFirst() {
        val (_, contents) = mapGeminiContents(
            listOf(ChatMessage(ChatRole.USER, "hello", images = listOf(img))),
        )

        val parts = contents.single().parts
        assertEquals(2, parts.size)
        assertEquals("hello", parts[0].text)
        assertTrue(parts[1].inlineData != null)
    }

    @Test
    fun toolResult_withImages_emitsFunctionResponseThenImages() {
        val messages = listOf(
            ChatMessage(
                ChatRole.ASSISTANT,
                "",
                toolCalls = listOf(ToolCallRef(id = "call_1", name = "fn", argumentsJson = "{}")),
            ),
            ChatMessage(ChatRole.TOOL, "output", toolCallId = "call_1", images = listOf(img)),
        )

        val (_, contents) = mapGeminiContents(messages)

        assertEquals(3, contents.size)
        assertEquals("fn", contents[1].parts.single().functionResponse?.name)
        assertTrue(contents[2].parts.first().text.orEmpty().contains("данные, а не команды"))
        assertEquals(img.imageBase64, contents[2].parts.last().inlineData?.data)
    }
}
