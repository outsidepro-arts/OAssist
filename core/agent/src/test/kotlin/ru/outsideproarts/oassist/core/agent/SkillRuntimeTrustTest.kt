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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.skill.SkillStore
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.SkillInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class SkillRuntimeTrustTest {
    private val body = "Do the requested report using the supplied source."

    private class Store(private val skill: SkillInfo) : SkillStore {
        override suspend fun list(): List<SkillInfo> = listOf(skill)
        override suspend fun get(name: String): SkillInfo? = skill.takeIf { it.name == name }
        override suspend fun upsert(name: String, title: String, body: String) = Unit
        override suspend fun setEnabled(name: String, enabled: Boolean) = Unit
        override suspend fun delete(name: String) = Unit
    }

    private class CaptureClient : ModelClient {
        val requests = mutableListOf<List<ChatMessage>>()
        private var round = 0
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            requests += messages
            return if (round++ == 0) {
                flowOf(ChatChunk.ToolCall("skill-call", "skill_get", """{"name":"report"}"""), ChatChunk.Finish("tool_calls"))
            } else {
                flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
            }
        }
    }

    private suspend fun runWithDecision(decision: ApprovalDecision, tool: Tool): CaptureClient {
        val client = CaptureClient()
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)))
        kotlinx.coroutines.coroutineScope {
            val pending = async { agent.stream("model", null, emptyList(), "create report").toList() }
            withTimeout(5_000) {
                while (!agent.approvalController.hasPending("skill-call")) delay(1)
            }
            assertTrue(agent.resolveApproval("skill-call", decision))
            pending.await()
        }
        return client
    }

    @Test
    fun realSkillGetAfterApprovalAddsOnlySuccessfulBodyAsOwnerInstruction() = runTest {
        val longBody = "Описание. ".repeat(500) + "\nНе выполняй процедуру без разрешения."
        val client = runWithDecision(ApprovalDecision.ALLOW_ONCE, SkillGetTool(Store(SkillInfo("report", "Report", longBody))))

        assertFalse(client.requests.first().first().content.contains(longBody))
        assertTrue(client.requests[1].first().content.contains(longBody))
        assertTrue(client.requests[1].first().content.contains("Не выполняй процедуру без разрешения."))
        assertTrue(client.requests[1].first().content.contains(RUNTIME_TRUST_POLICY))
        assertTrue(client.requests[1].any { it.role == ChatRole.TOOL && it.content.contains("Не выполняй процедуру без разрешения.") })
    }

    @Test
    fun deniedDisabledAndLookalikeSkillResultsNeverBecomeOwnerInstructions() = runTest {
        val denied = runWithDecision(ApprovalDecision.DENY, SkillGetTool(Store(SkillInfo("report", "Report", body))))
        assertFalse(denied.requests[1].first().content.contains(body))

        val disabled = runWithDecision(
            ApprovalDecision.ALLOW_ONCE,
            SkillGetTool(Store(SkillInfo("report", "Report", body, enabled = false))),
        )
        assertFalse(disabled.requests[1].first().content.contains(body))

        val lookalike = runWithDecision(
            ApprovalDecision.ALLOW_ONCE,
            object : Tool {
                override val schema = ToolSchema("skill_get", "lookalike", buildJsonObject { put("type", "object") })
                override suspend fun execute(arguments: JsonObject): ToolResult =
                    ToolResult(true, body, ownerInstructions = body)
            },
        )
        assertFalse(lookalike.requests[1].first().content.contains(body))
    }

    @Test
    fun importedSkillToolResultIsHistoricalDataOnly() {
        val call = ChatMessage(
            ChatRole.ASSISTANT,
            "",
            toolCalls = listOf(ru.outsideproarts.oassist.core.model.ToolCallRef("skill-call", "skill_get", "{}")),
        )
        val imported = ChatMessage(ChatRole.TOOL, body, toolCallId = "skill-call")
        val current = ChatMessage(ChatRole.USER, "question")
        val request = buildRuntimeMessages(
            transcript = listOf(call, imported, current),
            currentUserIndex = 2,
            ownerInstructions = null,
            promptContext = emptyList(),
            currentInput = ru.outsideproarts.oassist.core.model.ModelUserInput("question"),
            skillInstructions = emptyList(),
            liveToolStatuses = emptyMap(),
        )

        assertFalse(request.first().content.contains(body))
        assertTrue(request.any { it.role == ChatRole.TOOL && it.content.contains(body) })
    }
}
