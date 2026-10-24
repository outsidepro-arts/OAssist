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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopApprovalTest {

    private class ScriptedClient(private val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ru.outsideproarts.oassist.core.model.ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> = flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
    }

    private class CountingTool(
        private val name: String,
        override val groupKey: String = ToolGroups.key(ToolCategory.SCREEN_UI, ToolAccess.WRITE),
        override val requiredPermissions: List<String> = emptyList(),
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

    private class FakePermissionRequester(
        private val granted: Boolean = false,
        var outcome: PermissionRequestOutcome = PermissionRequestOutcome.GRANTED,
    ) : PermissionRequester {
        var requests = 0
        override fun isGranted(permission: String): Boolean = granted
        override suspend fun request(permissions: List<String>): PermissionRequestOutcome {
            requests++
            return outcome
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
    fun askGroup_pausesAndResumesOnAllowOnce() = runTest {
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
        assertEquals(tool.groupKey, approval.request.groupKey)
        assertEquals("Тестовое действие danger_call", approval.request.summary)
        val result = chunks.filterIsInstance<ChatChunk.ToolResult>().single()
        assertTrue(result.output.contains("выполнено:danger_call"))
        assertTrue(result.output.contains("Пользователь разрешил действие"))
        assertEquals(ChatChunk.Finish("stop"), chunks.last())
    }

    @Test
    fun deny_skipsExecution() = runTest {
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
    fun allowSession_coversNextCalls_untilSessionCleared() = runTest {
        val tool = CountingTool("danger_call")
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(toolCall("call-2", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(toolCall("call-3", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("три"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val policy = InMemoryApprovalPolicy()
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), approvalPolicy = policy)

        val job = async {
            agent.stream("m", null, emptyList(), "три раза").toList()
        }
        awaitPending(agent, "call-1")
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.ALLOW_SESSION))
        val chunks = job.await()

        assertEquals(3, tool.executions)
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().size)

        // Конец сессии — «на текущую сессию» забывается, следующий вызов спросит снова.
        policy.clearSession("CHAT")
        val client2 = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-4", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("снова"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent2 = AgentLoop(client2, ToolRegistry(listOf(tool)), approvalPolicy = policy)
        val job2 = async { agent2.stream("m", null, emptyList(), "ещё").toList() }
        awaitPending(agent2, "call-4")
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().size)
        assertTrue(agent2.resolveApproval("call-4", ApprovalDecision.DENY))
        job2.await()
    }

    @Test
    fun alwaysAllow_remembersGroup_withoutFurtherDialogs() = runTest {
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
        val policy = InMemoryApprovalPolicy()
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), approvalPolicy = policy)

        val job = async {
            agent.stream("m", null, emptyList(), "позвони дважды").toList()
        }
        awaitPending(agent, "call-1")
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.ALWAYS_ALLOW))
        val chunks = job.await()

        assertEquals(2, tool.executions)
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().size)
        assertEquals(ApprovalState.ALLOW, policy.state(tool.groupKey, "CHAT"))
    }

    @Test
    fun alwaysDeny_remembersGroup_silentlyDeniesNextCalls() = runTest {
        val tool = CountingTool("danger_call")
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(toolCall("call-2", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("итог"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val policy = InMemoryApprovalPolicy()
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), approvalPolicy = policy)

        val job = async {
            agent.stream("m", null, emptyList(), "дважды").toList()
        }
        awaitPending(agent, "call-1")
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.ALWAYS_DENY))
        val chunks = job.await()

        assertEquals(0, tool.executions)
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().size)
        val denials = chunks.filterIsInstance<ChatChunk.ToolResult>()
        assertEquals(2, denials.size)
        denials.forEach { assertTrue(it.output.contains("Отклонено пользователем")) }
        assertEquals(ApprovalState.DENY, policy.state(tool.groupKey, "CHAT"))
    }

    @Test
    fun screenLocked_addsUnlockNote() = runTest {
        val tool = CountingTool("danger_call")
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("ок"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), screenLocked = { true })

        val job = async { agent.stream("m", null, emptyList(), "сделай").toList() }
        awaitPending(agent, "call-1")
        val chunksBefore = agent.approvalController.hasPending("call-1")
        assertTrue(chunksBefore)
        assertTrue(agent.resolveApproval("call-1", ApprovalDecision.ALLOW_ONCE))
        val chunks = job.await()

        val approval = chunks.filterIsInstance<ChatChunk.ApprovalNeeded>().single()
        assertTrue(approval.request.screenLocked)
        val result = chunks.filterIsInstance<ChatChunk.ToolResult>().single()
        assertTrue(result.output.contains("экран был заблокирован"))
    }

    @Test
    fun serviceGroup_executesWithoutApproval() = runTest {
        val tool = object : Tool {
            override val groupKey = ToolGroups.SERVICE
            override val schema = ToolSchema(
                name = "bye",
                description = "Завершить",
                parameters = buildJsonObject { put("type", "object") },
            )
            override suspend fun execute(arguments: JsonObject) = ToolResult(true, "сессия завершена")
        }
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "bye"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("пока"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))
        val chunks = agent.stream("m", null, emptyList(), "пока").toList()

        assertTrue(chunks.none { it is ChatChunk.ApprovalNeeded })
        assertTrue(chunks.any { it is ChatChunk.ToolResult && it.output.contains("сессия завершена") })
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
        assertEquals(ToolGroups.UNKNOWN, approval.request.groupKey)
        assertTrue(chunks.any { it is ChatChunk.ToolResult && it.output.contains("Отклонено пользователем") })
    }

    @Test
    fun permissionGate_grantedAsksRetry_withoutExecution() = runTest {
        val tool = CountingTool(
            "danger_call",
            requiredPermissions = listOf("android.permission.CALL_PHONE"),
        )
        val requester = FakePermissionRequester(granted = false, outcome = PermissionRequestOutcome.GRANTED)
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val policy = InMemoryApprovalPolicy().apply {
            setRemembered(tool.groupKey, ApprovalState.ALLOW)
        }
        val agent = AgentLoop(
            client,
            ToolRegistry(listOf(tool)),
            approvalPolicy = policy,
            permissionRequester = requester,
        )
        val chunks = agent.stream("m", null, emptyList(), "позвони").toList()

        assertEquals(0, tool.executions)
        assertEquals(1, requester.requests)
        val result = chunks.filterIsInstance<ChatChunk.ToolResult>().single()
        assertTrue(result.output.contains("Попробуйте выполнить действие ещё раз"))
    }

    @Test
    fun permissionGate_denied_reportsDenial() = runTest {
        val tool = CountingTool(
            "danger_call",
            requiredPermissions = listOf("android.permission.CALL_PHONE"),
        )
        val requester = FakePermissionRequester(granted = false, outcome = PermissionRequestOutcome.DENIED)
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val policy = InMemoryApprovalPolicy().apply {
            setRemembered(tool.groupKey, ApprovalState.ALLOW)
        }
        val agent = AgentLoop(
            client,
            ToolRegistry(listOf(tool)),
            approvalPolicy = policy,
            permissionRequester = requester,
        )
        val chunks = agent.stream("m", null, emptyList(), "позвони").toList()

        assertEquals(0, tool.executions)
        val result = chunks.filterIsInstance<ChatChunk.ToolResult>().single()
        assertTrue(result.output.contains("действие отклонено"))
    }

    @Test
    fun permissionGate_alreadyGranted_executesThrough() = runTest {
        val tool = CountingTool(
            "danger_call",
            requiredPermissions = listOf("android.permission.CALL_PHONE"),
        )
        val requester = FakePermissionRequester(granted = true)
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(toolCall("call-1", "danger_call"), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val policy = InMemoryApprovalPolicy().apply {
            setRemembered(tool.groupKey, ApprovalState.ALLOW)
        }
        val agent = AgentLoop(
            client,
            ToolRegistry(listOf(tool)),
            approvalPolicy = policy,
            permissionRequester = requester,
        )
        val chunks = agent.stream("m", null, emptyList(), "позвони").toList()

        assertEquals(1, tool.executions)
        assertEquals(0, requester.requests)
        assertTrue(chunks.any { it is ChatChunk.ToolResult && it.output.contains("выполнено:danger_call") })
        assertFalse(chunks.any { it is ChatChunk.ApprovalNeeded })
    }
}
