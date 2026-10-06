package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopApprovalTest {

    private class ScriptedClient(private val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ru.outsideproarts.oassist.core.provider.ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> = flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
    }

    private class CountingTool(
        private val name: String,
        override val risk: ActionRisk = ActionRisk.ASK,
    ) : Tool {
        var executions = 0
        override val schema = ToolSchema(
            name = name,
            description = "Тестовый инструмент",
            parameters = buildJsonObject { put("type", "object") },
        )
        override fun describeForConfirmation(arguments: JsonObject): String = "Тестовое действие $name"
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult(true, "выполнено:$name")
        }
    }

    private fun toolCall(id: String, name: String) = ChatChunk.ToolCall(
        id = id,
        name = name,
        argumentsJson = "{}",
    )

    private suspend fun awaitPending(agent: AgentLoop, callId: String) {
        withTimeout(5_000) {
            while (!agent.approvalController.hasPending(callId)) delay(10)
        }
    }

    @Test
    fun askTool_pausesAndResumesOnAllowOnce() = runTest {
        val tool = CountingTool("danger_call")
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))

        val job = async {
            agent.stream("m", null, emptyList(), "позвони").toList()
        }
        awaitPending(agent, "call-1")
        assertEquals(0, tool.executions)
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.ALLOW_ONCE))
        val chunks = job.await()

        assertEquals(1, tool.executions)
        val approval = chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().single()
        assertEquals("call-1", approval.request.callId)
        assertEquals("danger_call", approval.request.toolName)
        assertEquals(ActionRisk.ASK, approval.request.risk)
        assertEquals("Тестовое действие danger_call", approval.request.summary)
        assertTrue(chunks.any { it is ChatChunk.ToolResult && it.output.contains("выполнено:danger_call") })
        assertEquals(ChatChunk.Finish("stop"), chunks.last())
    }

    @Test
    fun askTool_denySkipsExecution() = runTest {
        val tool = CountingTool("danger_call")
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("после отказа"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))

        val job = async {
            agent.stream("m", null, emptyList(), "позвони").toList()
        }
        awaitPending(agent, "call-1")
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.DENY))
        val chunks = job.await()

        assertEquals(0, tool.executions)
        assertTrue(chunks.any { it is ChatChunk.ToolResult && it.output.contains("Отклонено пользователем") })
    }

    @Test
    fun alwaysAllow_asksOnceThenAutoApproves() = runTest {
        val tool = CountingTool("danger_call")
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(toolCall("call-2", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("дважды"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))

        val job = async {
            agent.stream("m", null, emptyList(), "позвони дважды").toList()
        }
        awaitPending(agent, "call-1")
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.ALWAYS_ALLOW))
        val chunks = job.await()

        assertEquals(2, tool.executions)
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().size)
    }

    @Test
    fun autoTool_executesWithoutApproval() = runTest {
        val tool = CountingTool("safe_info", risk = ActionRisk.AUTO)
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "safe_info"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("ок"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))
        val chunks = agent.stream("m", null, emptyList(), "справка").toList()

        assertEquals(1, tool.executions)
        assertTrue(chunks.none { it is ChatChunk.ApprovalNeeded })
    }

    @Test
    fun unknownTool_failClosedRequiresApproval() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "no_such_tool"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("после"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(emptyList()))

        val job = async {
            agent.stream("m", null, emptyList(), "сделай несуществующее").toList()
        }
        awaitPending(agent, "call-1")
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.DENY))
        val chunks = job.await()

        val approval = chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().single()
        assertEquals(ActionRisk.ASK, approval.request.risk)
        assertTrue(chunks.any { it is ChatChunk.ToolResult && it.output.contains("Отклонено пользователем") })
    }
}
