package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopUsageTest {

    /** Клиент, отдающий подготовленные раунды по порядку (последний — финальный ответ). */
    private class RoundRobinClient(private val rounds: List<List<ChatChunk>>) : ModelClient {
        var calls = 0
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> {
            val round = rounds[minOf(calls, rounds.size - 1)]
            calls++
            return flow { round.forEach { emit(it) } }
        }
    }

    private class UsageRecorder : AgentEvents {
        val recorded = mutableListOf<ProviderUsage>()
        override suspend fun onTurnUsage(usage: ProviderUsage) {
            recorded.add(usage)
        }
    }

    @Test
    fun finish_carriesSingleRoundUsage() = runTest {
        val usage = ProviderUsage.of(inputTokens = 100, outputTokens = 20)
        val client = RoundRobinClient(
            listOf(listOf(ChatChunk.Content("ok"), ChatChunk.Finish(reason = "stop", usage = usage))),
        )
        val chunks = AgentLoop(client).stream("m", null, emptyList(), "q").toList()
        val finish = chunks.last() as ChatChunk.Finish
        assertEquals(usage, finish.usage)
    }

    @Test
    fun finish_sumsUsageAcrossToolRounds() = runTest {
        val round1 = ProviderUsage.of(inputTokens = 100, outputTokens = 10, costUsd = 0.001)
        val round2 = ProviderUsage.of(inputTokens = 300, outputTokens = 30)
        val client = RoundRobinClient(
            listOf(
                listOf(
                    ChatChunk.Content("ищу"),
                    ChatChunk.ToolCall(id = "c1", name = "device_state", argumentsJson = "{}"),
                    ChatChunk.Finish(reason = "tool_calls", usage = round1),
                ),
                listOf(
                    ChatChunk.Content("ответ"),
                    ChatChunk.Finish(reason = "stop", usage = round2),
                ),
            ),
        )
        val chunks = AgentLoop(client).stream("m", null, emptyList(), "q").toList()
        val finish = chunks.last() as ChatChunk.Finish
        assertEquals(400L, finish.usage!!.inputTokens)
        assertEquals(40L, finish.usage!!.outputTokens)
        assertEquals(440L, finish.usage!!.totalTokens)
        assertEquals(0.001, finish.usage!!.costUsd!!, 1e-9)
    }

    @Test
    fun events_onTurnUsageCalledOnceWithSum() = runTest {
        val round1 = ProviderUsage.of(totalTokens = 10)
        val round2 = ProviderUsage.of(totalTokens = 25)
        val client = RoundRobinClient(
            listOf(
                listOf(
                    ChatChunk.ToolCall(id = "c1", name = "device_state", argumentsJson = "{}"),
                    ChatChunk.Finish(reason = "tool_calls", usage = round1),
                ),
                listOf(ChatChunk.Finish(reason = "stop", usage = round2)),
            ),
        )
        val events = UsageRecorder()
        AgentLoop(client, events = events).stream("m", null, emptyList(), "q").toList()
        assertEquals(1, events.recorded.size)
        assertEquals(35L, events.recorded.single().totalTokens)
    }

    @Test
    fun finish_noUsage_staysNull() = runTest {
        val client = RoundRobinClient(
            listOf(listOf(ChatChunk.Content("ok"), ChatChunk.Finish(reason = "stop"))),
        )
        val events = UsageRecorder()
        val chunks = AgentLoop(client, events = events).stream("m", null, emptyList(), "q").toList()
        assertNull((chunks.last() as ChatChunk.Finish).usage)
        assertEquals(0, events.recorded.size)
    }

    @Test
    fun finish_intermediateFinishWithoutUsage_doesNotDoubleCount() = runTest {
        // Клиент шлёт промежуточный Finish (тул-раунд) без usage + финальный с usage:
        // итог не задвоен.
        val usage = ProviderUsage.of(totalTokens = 42)
        val client = RoundRobinClient(
            listOf(
                listOf(
                    ChatChunk.ToolCall(id = "c1", name = "device_state", argumentsJson = "{}"),
                    ChatChunk.Finish(reason = "tool_calls"),
                ),
                listOf(ChatChunk.Finish(reason = "stop", usage = usage)),
            ),
        )
        val chunks = AgentLoop(client).stream("m", null, emptyList(), "q").toList()
        assertEquals(42L, (chunks.last() as ChatChunk.Finish).usage!!.totalTokens)
    }
}
