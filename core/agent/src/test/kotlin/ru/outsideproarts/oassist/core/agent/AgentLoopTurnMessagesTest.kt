package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Экспорт транскрипта хода через onTurnMessages: assistant(tool_calls) + tool(tool_call_id)
 * + финальный assistant, без system/user — для накопления истории оверлея ассистента.
 */
class AgentLoopTurnMessagesTest {

    private class ScriptedClient(val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> =
            flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
    }

    private class PingTool : Tool {
        override val groupKey = ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE
        override val schema = ToolSchema(
            name = "ping",
            description = "тест",
            parameters = buildJsonObject { put("type", "object") },
        )

        override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult(true, "pong")
    }

    @Test
    fun simpleAnswer_exportsOnlyAssistantMessage() = runTest {
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("ответ"), ChatChunk.Finish("stop")))),
        )
        val agent = AgentLoop(client)
        var turn: List<ChatMessage>? = null

        agent.stream("m", null, emptyList(), "вопрос", onTurnMessages = { turn = it }).toList()

        val exported = requireNotNull(turn)
        assertEquals(1, exported.size)
        assertEquals(ChatRole.ASSISTANT, exported[0].role)
        assertEquals("ответ", exported[0].content)
    }

    @Test
    fun toolRound_exportsPairedToolMessages() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(PingTool())))
        var turn: List<ChatMessage>? = null

        agent.stream("m", null, emptyList(), "вопрос", onTurnMessages = { turn = it }).toList()

        val exported = requireNotNull(turn)
        assertEquals(3, exported.size)
        assertEquals(listOf("c1"), exported[0].toolCalls?.map { it.id })
        assertEquals(ChatRole.TOOL, exported[1].role)
        assertEquals("c1", exported[1].toolCallId)
        assertEquals("готово", exported[2].content)
    }

    @Test
    fun export_excludesSystemAndHistory() = runTest {
        val history = listOf(
            ChatMessage(ChatRole.USER, "старый вопрос"),
            ChatMessage(ChatRole.ASSISTANT, "старый ответ"),
        )
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("новый ответ"), ChatChunk.Finish("stop")))),
        )
        val agent = AgentLoop(client)
        var turn: List<ChatMessage>? = null

        agent.stream("m", "система", history, "вопрос", onTurnMessages = { turn = it }).toList()

        val exported = requireNotNull(turn)
        assertTrue(exported.none { it.role == ChatRole.SYSTEM })
        assertTrue(exported.none { it.content.contains("старый") })
    }

    @Test
    fun toolLoop_stopsAtConfiguredLimit() = runTest {
        val tool = PingTool()
        val client = object : ModelClient {
            val seen = mutableListOf<List<ChatMessage>>()
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
            ): Flow<ChatChunk> {
                seen.add(messages)
                return flowOf(
                    ChatChunk.ToolCall(id = "c${seen.size}", name = "ping", argumentsJson = "{}"),
                    ChatChunk.Finish("tool_calls"),
                )
            }
        }
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), maxToolRounds = 2)
        var turn: List<ChatMessage>? = null

        val chunks = agent.stream("m", null, emptyList(), "вопрос", onTurnMessages = { turn = it }).toList()

        assertEquals(2, client.seen.size)
        assertTrue(chunks.any { it is ChatChunk.Finish && it.reason == "max_tool_rounds" })
        val exported = requireNotNull(turn)
        assertEquals(4, exported.size)
    }

    @Test
    fun toolLoop_unlimitedCompletesBeyondOldCap() = runTest {
        val tool = PingTool()
        val client = object : ModelClient {
            var round = 0
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
            ): Flow<ChatChunk> {
                round++
                return if (round <= 8) {
                    flowOf(
                        ChatChunk.ToolCall(id = "c$round", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    )
                } else {
                    flowOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop"))
                }
            }
        }
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), maxToolRounds = 0)

        val chunks = agent.stream("m", null, emptyList(), "вопрос").toList()

        assertEquals(9, client.round)
        assertTrue(chunks.none { it is ChatChunk.Finish && it.reason == "max_tool_rounds" })
        assertTrue(chunks.any { it is ChatChunk.Content && it.delta == "готово" })
    }
}
