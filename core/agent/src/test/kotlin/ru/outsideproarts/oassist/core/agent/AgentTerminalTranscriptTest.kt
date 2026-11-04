package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentTerminalTranscriptTest {
    @Test fun cancellationAtApprovalKeepsAnExplicitUnstartedResult() = runTest {
        val tool = EffectTool()
        val turns = mutableListOf<List<ChatMessage>>()
        try {
            agent(tool, policy = InMemoryApprovalPolicy()).stream("m", null, emptyList(), "act", onTurnMessages = turns::add).collect {
                if (it is ChatChunk.ApprovalNeeded) throw CancellationException("approval cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, tool.effects)
        assertTrue(turns.single().last().content.contains("Not executed"))
        assertFalse(turns.single().last().content.contains("outcome unknown"))
    }
    @Test fun completedResultSurvivesCollectorCancellationExactlyOnce() = runTest {
        val tool = EffectTool()
        val turns = mutableListOf<List<ChatMessage>>()
        try {
            agent(tool).stream("m", null, emptyList(), "act", onTurnMessages = turns::add).collect {
                if (it is ChatChunk.ToolResult) throw CancellationException("collector stopped")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, tool.effects)
        assertEquals(1, turns.size)
        assertEquals("committed", turns.single().last().content)
        assertEquals("a", turns.single().last().toolCallId)
    }

    @Test fun interruptedBatchDistinguishesCompletedUnknownAndUnstartedCalls() = runTest {
        val entered = CompletableDeferred<Unit>()
        val tool = EffectTool {
            if (effects == 2) { entered.complete(Unit); awaitCancellation() }
        }
        val turns = mutableListOf<List<ChatMessage>>()
        val job = launch {
            agent(tool, listOf("a", "b", "c")).stream("m", null, emptyList(), "act", onTurnMessages = turns::add).collect()
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(2, tool.effects)
        val turn = turns.single()
        assertEquals(listOf("a", "b", "c"), turn.first().toolCalls!!.map { it.id })
        assertEquals(listOf("a", "b", "c"), turn.drop(1).map { it.toolCallId })
        assertEquals("committed", turn[1].content)
        assertTrue(turn[2].content.contains("outcome unknown"))
        assertTrue(turn[3].content.contains("Not executed"))
    }

    @Test fun providerFailureRetainsCompletedPairsAndPartialAnswer() = runTest {
        val tool = EffectTool()
        val turns = mutableListOf<List<ChatMessage>>()
        try {
            agent(tool).stream("m", null, emptyList(), "act", onTurnMessages = turns::add).collect()
            fail("Expected failure")
        } catch (error: IllegalStateException) { assertEquals("provider failure", error.message) }
        val turn = turns.single()
        assertEquals(1, tool.effects)
        assertEquals("committed", turn[1].content)
        assertEquals("partial refusal", turn[2].content)
        assertEquals("reason", turn[2].reasoning?.text)
    }

    private fun agent(tool: EffectTool, ids: List<String> = listOf("a"),
        policy: ApprovalPolicy = InMemoryApprovalPolicy().apply { applyPreset(AskPreset.ALLOW_ALL) }): AgentLoop {
        var requests = 0
        val client = object : ModelClient {
            override suspend fun fetchModels() = emptyList<ModelInfo>()
            override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean): Flow<ChatChunk> = flow {
                if (requests++ == 0) {
                    ids.forEach { emit(ChatChunk.ToolCall(it, "effect", "{}")) }
                    emit(ChatChunk.Finish("tool_calls"))
                } else {
                    emit(ChatChunk.Content("partial refusal"))
                    emit(ChatChunk.Reasoning("reason"))
                    throw IllegalStateException("provider failure")
                }
            }
        }
        return AgentLoop(client, ToolRegistry(listOf(tool)), approvalPolicy = policy)
    }

    private class EffectTool(val afterEffect: suspend EffectTool.() -> Unit = {}) : Tool {
        var effects = 0
        override val groupKey = ToolGroups.key(ToolCategory.SCREEN_UI, ToolAccess.WRITE)
        override val schema = ToolSchema("effect", "effect", JsonObject(emptyMap()))
        override suspend fun execute(arguments: JsonObject): ToolResult {
            effects++
            afterEffect()
            return ToolResult(true, "committed")
        }
    }
}
