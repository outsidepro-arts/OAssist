package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopTest {

    private class FakeModelClient(private val response: String) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> {
            return flowOf(
                ChatChunk.Content(response),
                ChatChunk.Finish(reason = "stop"),
            )
        }
    }

    @Test
    fun stream_emitsContentAndFinish() = runTest {
        val agent = AgentLoop(FakeModelClient("Привет!"))
        val chunks = agent.stream(
            modelId = "test-model",
            systemPrompt = "Ты ассистент",
            history = emptyList(),
            userMessage = "Привет",
        ).toList()

        assertEquals(2, chunks.size)
        assertEquals(ChatChunk.Content("Привет!"), chunks[0])
        assertEquals(ChatChunk.Finish("stop"), chunks[1])
    }

    @Test
    fun complete_assemblesFullResponse() = runTest {
        val agent = AgentLoop(FakeModelClient("Полный ответ"))
        val result = agent.complete(
            modelId = "test-model",
            systemPrompt = null,
            history = emptyList(),
            userMessage = "Вопрос",
        )
        assertEquals("Полный ответ", result)
    }

    @Test
    fun stream_includesSystemPromptAndHistory() = runTest {
        var capturedMessages: List<ChatMessage> = emptyList()
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
            ): Flow<ChatChunk> {
                capturedMessages = messages
                return flowOf(ChatChunk.Content("ok"))
            }
        }
        val agent = AgentLoop(client)
        val history = listOf(
            ChatMessage(ChatRole.USER, "Привет"),
            ChatMessage(ChatRole.ASSISTANT, "Привет!"),
        )
        agent.stream("m", "Система", history, "Как дела?").toList()

        assertEquals(4, capturedMessages.size)
        assertEquals(ChatRole.SYSTEM, capturedMessages[0].role)
        assertEquals("Система", capturedMessages[0].content)
        assertEquals(ChatRole.USER, capturedMessages[1].role)
        assertEquals(ChatRole.ASSISTANT, capturedMessages[2].role)
        assertEquals(ChatRole.USER, capturedMessages[3].role)
        assertEquals("Как дела?", capturedMessages[3].content)
    }
}
