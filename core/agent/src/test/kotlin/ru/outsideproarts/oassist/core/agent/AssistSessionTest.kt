package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ToolCallRef

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
    fun appendTurn_bigTurnDoesNotWipeHistory() {
        // Регрессия: ход крупнее прежнего лимита MAX_CHARS=16_000 стирал всю историю,
        // включая сам ход (web_fetch/ui_get_screen возвращают десятки КБ).
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()
        session.appendTurn(
            user = ChatMessage(ChatRole.USER, "первый вопрос"),
            turn = listOf(ChatMessage(ChatRole.ASSISTANT, "первый ответ")),
        )
        val big = "x".repeat(30_000)
        session.appendTurn(
            user = ChatMessage(ChatRole.USER, "второй вопрос"),
            turn = listOf(
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(ToolCallRef(id = "call_1", name = "web_fetch", argumentsJson = "{}")),
                ),
                ChatMessage(ChatRole.TOOL, content = big, toolCallId = "call_1"),
                ChatMessage(ChatRole.ASSISTANT, "готово"),
            ),
        )

        val history = session.history
        assertEquals(6, history.size)
        assertEquals("первый вопрос", history[0].content)
        assertTrue("вывод инструмента должен сохраниться целиком", history[4].content == big)
        assertEquals("готово", history.last().content)
        assertToolPairsIntact(history)
    }

    @Test
    fun appendTurn_keepsWholeHistoryWithoutTrims() {
        // Новое требование: история хранится как есть — без лимитов сообщений/символов
        // и без бюджета образов; усечением владеет будущий механизм компактизации.
        AssistSessionStore.clear()
        val session = AssistSessionStore.getOrCreate()
        val img = ImagePart(imageBase64 = "QUJD")
        val big = "x".repeat(2_000)
        repeat(30) { i ->
            session.appendTurn(
                user = ChatMessage(ChatRole.USER, "вопрос $i"),
                turn = listOf(
                    ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = "",
                        toolCalls = listOf(ToolCallRef(id = "call_$i", name = "tool", argumentsJson = "{}")),
                    ),
                    ChatMessage(ChatRole.TOOL, content = big, toolCallId = "call_$i", images = listOf(img)),
                    ChatMessage(ChatRole.ASSISTANT, "ответ $i"),
                ),
            )
        }

        val history = session.history
        // 30 ходов по 4 сообщения; раньше вырезались и сообщения, и образы.
        assertEquals(30 * 4, history.size)
        assertEquals(30, history.count { it.images.isNotEmpty() })
        assertToolPairsIntact(history)
    }
}
