package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredTurnTest {

    @Test
    fun modelMessages_prefixesUserAndKeepsTurn() {
        val toolCall = ToolCallRef(id = "call_1", name = "device_state", argumentsJson = "{}")
        val turn = listOf(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Смотрю…",
                toolCalls = listOf(toolCall),
            ),
            ChatMessage(role = ChatRole.TOOL, content = "torch=off", toolCallId = "call_1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "Фонарик выключен"),
        )
        val stored = StoredTurn(
            userText = "Какой статус?",
            userAudio = AudioPart(wavBase64 = "AAAA", durationSec = 2),
            userImages = listOf(ImagePart(imageBase64 = "BBBB")),
            thought = "Смотрю…",
            tools = listOf(StoredTool(name = "device_state", output = "torch=off")),
            answer = "Фонарик выключен",
            usage = ProviderUsage.of(inputTokens = 10, outputTokens = 5),
            turn = turn,
        )

        val messages = stored.modelMessages()

        assertEquals(4, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("Какой статус?", messages[0].content)
        assertEquals("AAAA", messages[0].audio?.wavBase64)
        assertEquals("BBBB", messages[0].images.single().imageBase64)
        assertEquals(turn, messages.drop(1))
    }

    @Test
    fun modelMessages_withoutMedia() {
        val stored = StoredTurn(userText = "привет", answer = "здравствуйте")
        val messages = stored.modelMessages()
        assertEquals(1, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertTrue(messages[0].images.isEmpty())
    }
}
