package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.diagnostics.*
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentDiagnosticContractTest {
    @Test fun recordsMessagesAndReturnedReasoning() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events += event }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        try {
            val model = object : ModelClient {
                override suspend fun fetchModels(): List<ModelInfo> = emptyList()
                override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>,
                    tools: List<ToolSchemaDto>, reasoningEnabled: Boolean): Flow<ChatChunk> =
                    flowOf(ChatChunk.Reasoning("thought"), ChatChunk.Content("answer"), ChatChunk.Finish("stop"))
            }
            AgentLoop(model).stream("m", "instruction", emptyList(), "question").toList()
            val start = events.single { it.kind == DiagnosticKind.OPERATION_START }
            val round = events.single { it.kind == DiagnosticKind.AGENT_ROUND }
            val response = events.single { it.kind == DiagnosticKind.PROVIDER_RESPONSE }
            val end = events.single { it.kind == DiagnosticKind.OPERATION_END }
            assertEquals(start.operationId, round.operationId)
            assertEquals(start.operationId, end.operationId)
            assertTrue(round.content!!.contains("question"))
            assertTrue(response.content!!.contains("thought"))
            assertTrue(response.content!!.contains("answer"))
        } finally {
            Diagnostics.swapForTest(previous)
        }
    }
}
