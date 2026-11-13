package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            reasoningEnabled: Boolean,
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
    fun stream_buildsUpstreamOnStreamContext() = runTest {
        val recordThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val callbackThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = object : ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder {
            override fun record(
                event: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent,
                secrets: Collection<String>,
            ) {
                recordThreads += Thread.currentThread().name
            }

            override fun health() = ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth(
                available = true, contentEnabled = true, droppedEvents = 0, storedBytes = 0,
            )
        }
        val events = object : AgentEvents {
            override suspend fun onToolCall(toolName: String, argumentsJson: String, summary: String) {
                callbackThreads += Thread.currentThread().name
            }

            override suspend fun onTurnUsage(usage: ru.outsideproarts.oassist.core.model.ProviderUsage) {
                callbackThreads += Thread.currentThread().name
            }
        }
        val previous = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.swapForTest(recorder)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "agent-stream-context") }
        val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "agent-callback-context") }
        try {
            val agent = AgentLoop(
                FakeModelClient("Привет!"),
                events = events,
                streamContext = executor.asCoroutineDispatcher(),
                callbackContext = callbackExecutor.asCoroutineDispatcher(),
            )
            agent.stream(
                modelId = "test-model",
                systemPrompt = null,
                history = emptyList(),
                userMessage = "q",
                onTurnMessages = { callbackThreads += Thread.currentThread().name },
            ).toList()
            // Весь upstream стрима (запрос, диагностика, тулы) строится в streamContext,
            // а не на потоке коллектора — иначе сборка агента фризит UI.
            assertTrue("диагностика стрима пишется не из streamContext: $recordThreads", recordThreads.isNotEmpty())
            assertTrue("диагностика стрима пишется не из streamContext: $recordThreads", recordThreads.all { it.startsWith("agent-stream-context") })
            // Колбэки слоёв возвращаются в callbackContext: их очереди записи
            // однонитные и рассчитывают на поток коллектора.
            assertTrue("колбэки слоёв вызваны не из callbackContext: $callbackThreads", callbackThreads.isNotEmpty())
            assertTrue("колбэки слоёв вызваны не из callbackContext: $callbackThreads", callbackThreads.all { it.startsWith("agent-callback-context") })
        } finally {
            executor.shutdown()
            callbackExecutor.shutdown()
            ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.swapForTest(previous)
        }
    }

    @Test
    fun stream_defaultContextKeepsCallbacksOnCollectorThread() = runTest {
        val collectorThread = Thread.currentThread().name
        val seenThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = object : ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder {
            override fun record(
                event: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent,
                secrets: Collection<String>,
            ) {
                seenThreads += Thread.currentThread().name
            }

            override fun health() = ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth(
                available = true, contentEnabled = true, droppedEvents = 0, storedBytes = 0,
            )
        }
        val previous = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.swapForTest(recorder)
        try {
            val agent = AgentLoop(FakeModelClient("Привет!"))
            agent.stream(
                modelId = "test-model",
                systemPrompt = null,
                history = emptyList(),
                userMessage = "q",
                onTurnMessages = { seenThreads += Thread.currentThread().name },
            ).toList()
            // Дефолтные контексты — прежнее поведение: диагностика и колбэки
            // идут на потоке коллектора.
            assertTrue("дефолтный контекст сместил побочные эффекты: $seenThreads", seenThreads.isNotEmpty())
            assertTrue("дефолтный контекст сместил побочные эффекты: $seenThreads", seenThreads.all { it == collectorThread })
        } finally {
            ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.swapForTest(previous)
        }
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
                reasoningEnabled: Boolean,
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
        assertTrue(capturedMessages[0].content.contains("Система"))
        assertTrue(capturedMessages[0].content.contains(RUNTIME_TRUST_POLICY))
        assertEquals(ChatRole.USER, capturedMessages[1].role)
        assertEquals(ChatRole.ASSISTANT, capturedMessages[2].role)
        assertEquals(ChatRole.USER, capturedMessages[3].role)
        val previous = Json.parseToJsonElement(capturedMessages[1].content).jsonObject
        assertEquals("history", previous["kind"]!!.jsonPrimitive.content)
        assertEquals("Привет", previous["content"]!!.jsonPrimitive.content)
        val current = Json.parseToJsonElement(capturedMessages[3].content).jsonObject
        assertEquals("current_user_input", current["kind"]!!.jsonPrimitive.content)
        assertEquals("Как дела?", current["request"]!!.jsonPrimitive.content)
    }
}
