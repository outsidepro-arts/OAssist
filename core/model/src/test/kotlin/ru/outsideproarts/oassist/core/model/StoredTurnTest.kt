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
            work = listOf(
                WorkEvent.Thought("Смотрю…"),
                WorkEvent.Tool(name = "device_state", output = "torch=off"),
            ),
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
    fun modelMessages_withoutTranscriptOrAnswer_keepsOnlyUser() {
        for (answer in listOf("", "  ")) {
            val messages = StoredTurn(userText = "привет", answer = answer).modelMessages()
            assertEquals(listOf(ChatMessage(ChatRole.USER, "привет")), messages)
        }
    }

    @Test
    fun modelMessages_withTranscript_doesNotAppendDisplayAnswer() {
        val transcript = listOf(ChatMessage(ChatRole.ASSISTANT, "Сохранённая реплика"))
        val messages = StoredTurn(userText = "вопрос", answer = "Текст для интерфейса", turn = transcript).modelMessages()
        assertEquals(listOf(ChatMessage(ChatRole.USER, "вопрос")) + transcript, messages)
    }

    @Test
    fun modelMessages_withoutTranscript_replaysSavedAnswer() {
        val stored = StoredTurn(userText = "привет", answer = "здравствуйте")
        val messages = stored.modelMessages()
        assertEquals(
            listOf(ChatMessage(ChatRole.USER, "привет"), ChatMessage(ChatRole.ASSISTANT, "здравствуйте")),
            messages,
        )
        assertTrue(messages[0].images.isEmpty())
    }
}
