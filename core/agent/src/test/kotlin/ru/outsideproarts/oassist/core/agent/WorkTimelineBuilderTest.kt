package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.provider.ChatChunk

class WorkTimelineBuilderTest {

    @Test
    fun reasoningAndTools_interleaveChronologically() {
        val b = WorkTimelineBuilder()
        b.onChunk(ChatChunk.Reasoning("сначала подумаю"))
        b.onChunk(ChatChunk.ToolCall(id = "c1", name = "device_state", argumentsJson = "{}"))
        b.onChunk(ChatChunk.ToolResult(id = "c1", name = "device_state", output = "torch=off"))
        b.onChunk(ChatChunk.Reasoning("а теперь уточню"))
        b.onChunk(ChatChunk.Content("комментарий агента", isToolComment = true))
        b.onChunk(ChatChunk.Content("готово"))

        val work = b.work
        assertEquals(4, work.size)
        assertEquals("сначала подумаю", (work[0] as WorkEvent.Thought).text)
        assertEquals("device_state", (work[1] as WorkEvent.Tool).name)
        assertEquals("torch=off", (work[1] as WorkEvent.Tool).output)
        // Смена «рода» после тула — новый мыслительный блок.
        assertEquals("а теперь уточню", (work[2] as WorkEvent.Thought).text)
        // Род сменился (reasoning → комментарий) — третий блок, не склейка.
        assertEquals("комментарий агента", (work[3] as WorkEvent.Thought).text)
        assertEquals("готово", b.answerText)
    }

    @Test
    fun sameKindReasoningDeltas_mergeIntoOneBlock() {
        val b = WorkTimelineBuilder()
        b.onChunk(ChatChunk.Reasoning("часть "))
        b.onChunk(ChatChunk.Reasoning("одна"))
        assertEquals(listOf(WorkEvent.Thought("часть одна")), b.work)
    }

    @Test
    fun commentText_excludesReasoning() {
        val b = WorkTimelineBuilder()
        b.onChunk(ChatChunk.Reasoning("мысли не вслух"))
        b.onChunk(ChatChunk.Content("ищу контакт", isToolComment = true))
        assertEquals("ищу контакт", b.commentText)
    }

    @Test
    fun signatureOnlyReasoning_doesNotCreateEvent() {
        val b = WorkTimelineBuilder()
        val changed = b.onChunk(ChatChunk.Reasoning(delta = "", signature = "sig"))
        assertFalse(changed)
        assertTrue(b.work.isEmpty())
    }

    @Test
    fun toolResult_patchesPendingCallByName() {
        val b = WorkTimelineBuilder()
        b.onChunk(ChatChunk.ToolCall(id = "c1", name = "web_fetch", argumentsJson = "{}"))
        b.onChunk(ChatChunk.ToolCall(id = "c2", name = "device_state", argumentsJson = "{}"))
        b.onChunk(ChatChunk.ToolResult(id = "c1", name = "web_fetch", output = "ok", isError = false))

        val tools = b.work.filterIsInstance<WorkEvent.Tool>()
        assertEquals("ok", tools[0].output)
        assertNull(tools[1].output)
    }

    @Test
    fun finish_setsUsage() {
        val b = WorkTimelineBuilder()
        b.onChunk(ChatChunk.Finish("stop", usage = ProviderUsage.of(inputTokens = 5, outputTokens = 3)))
        assertEquals(8L, b.usage?.totalTokens)
    }

    @Test
    fun isEmpty_coversAnswerAndWork() {
        assertTrue(WorkTimelineBuilder().isEmpty)
        val b = WorkTimelineBuilder()
        b.onChunk(ChatChunk.Content("ответ"))
        assertFalse(b.isEmpty)
    }
}
