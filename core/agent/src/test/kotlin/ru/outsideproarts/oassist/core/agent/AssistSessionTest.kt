package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ImagePart
import ru.outsideproarts.oassist.core.provider.ToolCallRef

class AssistSessionTest {

    private fun assertToolPairsIntact(history: List<ChatMessage>) {
        assertEquals(ChatRole.USER, history.first().role)
        val openCalls = mutableSetOf<String>()
        history.forEach { message ->
            when (message.role) {
                ChatRole.USER -> {
                    assertTrue("инструментальные пары не пересекают реплики", openCalls.isEmpty())
                    openCalls.clear()
                }
                ChatRole.ASSISTANT -> openCalls.addAll(message.toolCalls.orEmpty().map { it.id })
                ChatRole.TOOL -> {
                    val id = requireNotNull(message.toolCallId)
                    assertTrue("tool без предшествующего tool_call: $id", openCalls.remove(id))
                }
                ChatRole.SYSTEM -> Unit
            }
        }
    }

    @Test
    fun appendTurn_keepsToolPairsIntact() {
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()

        session.appendTurn(
            user = ChatMessage(ChatRole.USER, "включи фонарик"),
            turn = listOf(
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(ToolCallRef(id = "call_1", name = "flashlight", argumentsJson = "{}")),
                ),
                ChatMessage(role = ChatRole.TOOL, content = "on", toolCallId = "call_1"),
                ChatMessage(ChatRole.ASSISTANT, "Фонарик включён"),
            ),
        )

        val history = session.history
        assertEquals(4, history.size)
        assertEquals(ChatRole.USER, history[0].role)
        assertEquals("call_1", history[1].toolCalls?.single()?.id)
        assertEquals("call_1", history[2].toolCallId)
        assertEquals(ChatRole.ASSISTANT, history[3].role)
        assertToolPairsIntact(history)
    }

    @Test
    fun clear_destroysContext() {
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()
        session.screenContext = "какой-то экран"
        session.appendTurn(
            ChatMessage(ChatRole.USER, "привет"),
            listOf(ChatMessage(ChatRole.ASSISTANT, "привет")),
        )

        AssistSessionStore.clear()

        assertNull(AssistSessionStore.current())
        val fresh = AssistSessionStore.getOrCreate()
        assertTrue(fresh.history.isEmpty())
        assertNull(fresh.screenContext)
    }

    @Test
    fun trim_dropsOldestTurnsKeepingPairs() {
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()
        repeat(30) { i ->
            session.appendTurn(
                user = ChatMessage(ChatRole.USER, "вопрос $i"),
                turn = listOf(
                    ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = "",
                        toolCalls = listOf(ToolCallRef(id = "call_$i", name = "tool", argumentsJson = "{}")),
                    ),
                    ChatMessage(role = ChatRole.TOOL, content = "результат $i", toolCallId = "call_$i"),
                    ChatMessage(ChatRole.ASSISTANT, "ответ $i"),
                ),
            )
        }

        val history = session.history
        assertTrue(history.size <= AssistSession.MAX_MESSAGES)
        assertToolPairsIntact(history)
    }

    @Test
    fun trim_byChars_keepsPairs() {
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()
        val big = "x".repeat(2_000)
        repeat(12) { i ->
            session.appendTurn(
                user = ChatMessage(ChatRole.USER, "вопрос $i"),
                turn = listOf(
                    ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = "",
                        toolCalls = listOf(ToolCallRef(id = "call_$i", name = "tool", argumentsJson = "{}")),
                    ),
                    ChatMessage(role = ChatRole.TOOL, content = big, toolCallId = "call_$i"),
                    ChatMessage(ChatRole.ASSISTANT, "ответ $i"),
                ),
            )
        }

        val history = session.history
        assertTrue(history.sumOf { it.content.length } <= AssistSession.MAX_CHARS + 4_000)
        assertToolPairsIntact(history)
    }

    @Test
    fun trim_stripsOldImagesKeepingRecent() {
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()
        val img = ImagePart(imageBase64 = "QUJD")
        repeat(6) { i ->
            session.appendTurn(
                user = ChatMessage(ChatRole.USER, "question $i"),
                turn = listOf(ChatMessage(ChatRole.ASSISTANT, "answer $i", images = listOf(img))),
            )
        }

        val history = session.history
        val withImages = history.filter { it.images.isNotEmpty() }
        assertEquals(MAX_IMAGE_MESSAGES, withImages.size)
        assertEquals(listOf("answer 2", "answer 3", "answer 4", "answer 5"), withImages.map { it.content })
    }
}
