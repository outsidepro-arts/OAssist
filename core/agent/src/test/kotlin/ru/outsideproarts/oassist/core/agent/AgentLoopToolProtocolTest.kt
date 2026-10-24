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
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Регрессия зацикливания «один тул × N раундов»: результат тула возвращается
 * парой assistant(tool_calls) + tool(tool_call_id), которую модель узнаёт, —
 * второго раунда вызовов нет.
 */
class AgentLoopToolProtocolTest {

    private class ScriptedClient(val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        val seenMessages = mutableListOf<List<ChatMessage>>()
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            seenMessages.add(messages)
            return flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
        }
    }

    private class PingTool : Tool {
        var executions = 0
        override val groupKey = ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE
        override val schema = ToolSchema(
            name = "ping",
            description = "Пинг",
            parameters = buildJsonObject { put("type", "object") },
        )
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult(true, "pong")
        }
    }

    @Test
    fun toolResult_formsAssistantToolPair_noRepeatRound() = runTest {
        val tool = PingTool()
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                    // После tool-результата модель отвечает текстом — третьего раунда нет.
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))
        agent.stream("m", null, emptyList(), "пинг").toList()

        assertEquals(1, tool.executions)
        assertEquals(2, client.seenMessages.size)

        val secondRound = client.seenMessages[1]
        val assistantTurn = secondRound.filter { it.role == ChatRole.ASSISTANT }.last()
        assertEquals(listOf("c1"), assistantTurn.toolCalls?.map { it.id })
        assertEquals("ping", assistantTurn.toolCalls?.single()?.name)

        val toolTurn = secondRound.single { it.role == ChatRole.TOOL }
        assertEquals("c1", toolTurn.toolCallId)
        assertTrue(toolTurn.content.contains("pong"))

        // Старого текстового маркера в истории нет.
        assertFalse(secondRound.any { it.content.contains("[tool:") })
        assertEquals(ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE, tool.groupKey)
    }

    @Test
    fun contentBeforeToolCalls_markedAsToolComment_finalAnswerIsNot() = runTest {
        val tool = PingTool()
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.Content("ищу контакт"),
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))
        val chunks = agent.stream("m", null, emptyList(), "пинг").toList()

        val comment = chunks.filterIsInstance<ChatChunk.Content>()
            .single { it.delta.contains("ищу контакт") }
        assertTrue(comment.isToolComment)
        val answer = chunks.filterIsInstance<ChatChunk.Content>()
            .single { it.delta == "готово" }
        assertFalse(answer.isToolComment)
    }
}
