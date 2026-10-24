package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Reasoning-поток: live-форвард наружу и подшивка `ChatMessage.reasoning`
 * (текст + подпись Anthropic) к сообщениям хода для replay в истории.
 */
class AgentLoopReasoningTest {

    private class ScriptedClient(
        private val rounds: ArrayDeque<List<ChatChunk>>,
        // Ловит флаг reasoningEnabled, переданный в каждый вызов.
        val seenReasoningEnabled: MutableList<Boolean> = mutableListOf(),
    ) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            seenReasoningEnabled += reasoningEnabled
            return flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
        }
    }

    @Test
    fun reasoning_isForwardedLive_beforeContent() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.Reasoning("сперва думаю"),
                        ChatChunk.Reasoning("и ещё"),
                        ChatChunk.Content("ответ"),
                        ChatChunk.Finish("stop"),
                    ),
                ),
            ),
        )
        val chunks = AgentLoop(client).stream("m", null, emptyList(), "hi").toList()

        val reasoning = chunks.filterIsInstance<ChatChunk.Reasoning>()
        assertEquals(listOf("сперва думаю", "и ещё"), reasoning.map { it.delta })
        assertTrue(chunks.indexOfFirst { it is ChatChunk.Reasoning } < chunks.indexOfFirst { it is ChatChunk.Content })
        // Мысли не попадают в текст ответа.
        assertEquals("ответ", chunks.filterIsInstance<ChatChunk.Content>().single().delta)
    }

    @Test
    fun finalAssistantMessage_carriesReasoningBlock() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.Reasoning("ход мыслей"),
                        ChatChunk.Reasoning(delta = "", signature = "sig_1"),
                        ChatChunk.Content("ответ"),
                        ChatChunk.Finish("stop"),
                    ),
                ),
            ),
        )
        var turn: List<ChatMessage> = emptyList()
        AgentLoop(client).stream("m", null, emptyList(), "hi", onTurnMessages = { turn = it }).toList()

        val assistant = turn.single { it.role == ru.outsideproarts.oassist.core.model.ChatRole.ASSISTANT }
        assertEquals("ход мыслей", assistant.reasoning?.text)
        assertEquals("sig_1", assistant.reasoning?.signature)
        assertEquals("ответ", assistant.content)
    }

    @Test
    fun toolRoundAssistantMessage_carriesReasoningForReplay() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.Reasoning("думаю над тулом"),
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        var turn: List<ChatMessage> = emptyList()
        val tool = object : Tool {
            override val schema = ToolSchema(
                name = "ping",
                description = "тест",
                parameters = kotlinx.serialization.json.buildJsonObject { put("type", "object") },
            )
            override val groupKey = ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): ToolResult =
                ToolResult(true, "pong")
        }
        AgentLoop(client, ToolRegistry(listOf(tool)))
            .stream("m", null, emptyList(), "hi", onTurnMessages = { turn = it })
            .toList()

        val withCalls = turn.first { it.toolCalls != null }
        assertEquals("думаю над тулом", withCalls.reasoning?.text)
        // Финальный ответ — без reasoning (мышление было в тул-раунде).
        val final = turn.last { it.role == ru.outsideproarts.oassist.core.model.ChatRole.ASSISTANT }
        assertEquals("готово", final.content)
    }

    @Test
    fun reasoningEnabled_isPassedToClient() = runTest {
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("ок"), ChatChunk.Finish("stop")))),
        )
        AgentLoop(client, reasoningEnabled = true)
            .stream("m", null, emptyList(), "hi")
            .toList()

        assertEquals(listOf(true), client.seenReasoningEnabled)
    }

    @Test
    fun reasoningDisabled_byDefault() = runTest {
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("ок"), ChatChunk.Finish("stop")))),
        )
        AgentLoop(client).stream("m", null, emptyList(), "hi").toList()

        assertEquals(listOf(false), client.seenReasoningEnabled)
    }
}
