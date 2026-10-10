package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AskPreset
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopAbruptTest {

    private class FixedClient(private val chunks: List<ChatChunk>) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> = flowOf(*chunks.toTypedArray())
    }

    private class RoundsClient(private val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> = flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
    }

    private fun stream(client: ModelClient) = client.let { AgentLoop(it).stream(
        modelId = "m",
        systemPrompt = "sys",
        history = emptyList(),
        userMessage = "hi",
    ) }

    @Test
    fun stream_forwardsAbruptFlag() = runTest {
        val chunks = stream(
            FixedClient(listOf(ChatChunk.Content("част"), ChatChunk.Finish("stop", abrupt = true))),
        ).toList()
        val finish = chunks.filterIsInstance<ChatChunk.Finish>().single()
        assertTrue(finish.abrupt)
        assertEquals("част", (chunks.first() as ChatChunk.Content).delta)
    }

    @Test
    fun stream_cleanFinish_notAbrupt() = runTest {
        val chunks = stream(
            FixedClient(listOf(ChatChunk.Content("ok"), ChatChunk.Finish("stop"))),
        ).toList()
        val finish = chunks.filterIsInstance<ChatChunk.Finish>().single()
        assertFalse(finish.abrupt)
    }

    @Test
    fun stream_intermediateAbruptRound_doesNotMarkFinalAnswer() = runTest {
        // Обрывался промежуточный тула-раунд (аварийный Finish), но финальный
        // ответ модели пришёл целиком — пометка «Ответ оборвался» не нужна.
        val tool = object : Tool {
            override val schema = ToolSchema(
                name = "t1",
                description = "Тестовый инструмент",
                parameters = buildJsonObject { put("type", "object") },
            )
            override val groupKey: String = ToolGroups.key(ToolCategory.SCREEN_UI, ToolAccess.WRITE)
            override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult(true, "ok")
        }
        val client = RoundsClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "call-1", name = "t1", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls", abrupt = true),
                    ),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop", abrupt = false)),
                ),
            ),
        )
        val policy = InMemoryApprovalPolicy().apply { applyPreset(AskPreset.ALLOW_ALL) }
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), approvalPolicy = policy)
        val chunks = agent.stream("m", null, emptyList(), "hi").toList()
        val finish = chunks.filterIsInstance<ChatChunk.Finish>().single()
        assertFalse(finish.abrupt)
    }
}
