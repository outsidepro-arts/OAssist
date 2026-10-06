package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ChatChunk

class AgentMessageAssemblerTest {

    @Test
    fun splitsThoughtToolsAndAnswer() {
        val asm = AgentMessageAssembler()
        asm.onChunk(ChatChunk.Content("ищу контакт…", isToolComment = true))
        asm.onChunk(ChatChunk.ToolCall(id = "c1", name = "contact_find", argumentsJson = "{}"))
        asm.onChunk(ChatChunk.ToolResult(id = "c1", name = "contact_find", output = "Мама, +7"))
        asm.onChunk(ChatChunk.Content("Готово: номер найден."))
        asm.onChunk(ChatChunk.Finish("stop"))
        val parts = asm.snapshot()
        assertEquals("ищу контакт…", parts.thought)
        assertEquals(1, parts.tools.size)
        assertEquals("contact_find", parts.tools[0].name)
        assertEquals("Мама, +7", parts.tools[0].output)
        assertEquals("Готово: номер найден.", parts.answer)
    }

    @Test
    fun approvalAndFinish_ignored() {
        val asm = AgentMessageAssembler()
        asm.onChunk(ChatChunk.Content("ответ"))
        asm.onChunk(ChatChunk.Finish("stop"))
        assertTrue(asm.snapshot().tools.isEmpty())
        assertEquals("ответ", asm.answerText())
    }
}
