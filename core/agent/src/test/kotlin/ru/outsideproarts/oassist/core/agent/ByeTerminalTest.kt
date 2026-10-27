package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class ByeTerminalTest {
    private class Client(vararg scripted: List<ChatChunk>) : ModelClient {
        private val rounds = ArrayDeque(scripted.toList())
        val requests = mutableListOf<List<ChatMessage>>()
        val advertised = mutableListOf<List<ToolSchemaDto>>()
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            requests += messages.toList()
            advertised += tools
            return flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
        }
    }

    private class Events : AgentEvents {
        val calls = mutableListOf<String>()
        val results = mutableListOf<String>()
        override suspend fun onToolCall(toolName: String, argumentsJson: String, summary: String) {
            calls += toolName
        }
        override suspend fun onToolResult(toolName: String, output: String, success: Boolean) {
            results += toolName
        }
    }

    private fun bye(arguments: String, id: String = "bye-1") =
        ChatChunk.ToolCall(id = id, name = "bye", argumentsJson = arguments)

    @Test
    fun successfulBuiltinReturnsSingleFarewellWithoutAnotherModelCallAndPersistsTurn() = runTest {
        val client = Client(listOf(
            ChatChunk.Content("Прощание до вызова."),
            bye("""{"farewell":"Спасибо, до скорой встречи!"}"""),
            ChatChunk.Finish("tool_calls"),
        ))
        val events = Events()
        val agent = AgentLoop(client, ToolRegistry(listOf(ByeTool())), events = events, fallbackFarewell = "До встречи!")
        var turn = emptyList<ChatMessage>()
        val chunks = agent.stream("m", null, emptyList(), "Пока", onTurnMessages = { turn = it }).toList()

        assertEquals(1, client.requests.size)
        assertEquals(listOf(ChatChunk.Content("Спасибо, до скорой встречи!", isTerminalFarewell = true)),
            chunks.filterIsInstance<ChatChunk.Content>())
        assertTrue(chunks.filterIsInstance<ChatChunk.Finish>().single().closeSession)
        assertTrue(events.calls.isEmpty())
        assertTrue(events.results.isEmpty())
        assertEquals(listOf(ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT), turn.map { it.role })
        assertEquals("bye-1", turn[1].toolCallId)
        assertEquals("Спасибо, до скорой встречи!", turn[2].content)
        assertEquals(1, turn[0].toolCalls?.size)
        val schema = client.advertised.single().single().function.parameters.toString()
        assertTrue(schema.contains("\"farewell\""))
        assertTrue(schema.contains("\"required\""))
    }

    @Test
    fun legacyMissingOrBlankFarewellUsesOneFallbackInPriorityOrder() = runTest {
        val cases = listOf(
            Triple("{}", "Текст до вызова.", "Текст до вызова."),
            Triple("""{"farewell":"  "}""", "", "До встречи!"),
        )
        for ((arguments, pretext, expected) in cases) {
            val client = Client(listOf(ChatChunk.Content(pretext), bye(arguments), ChatChunk.Finish("tool_calls")))
            val chunks = AgentLoop(client, ToolRegistry(listOf(ByeTool())), fallbackFarewell = "До встречи!")
                .stream("m", null, emptyList(), "Пока").toList()
            assertEquals(expected, chunks.filterIsInstance<ChatChunk.Content>().single().delta)
            assertTrue(chunks.filterIsInstance<ChatChunk.Finish>().single().closeSession)
            assertEquals(1, client.requests.size)
        }
    }

    @Test
    fun invalidPayloadOrMissingFallbackDoesNotClose() = runTest {
        for (arguments in listOf("{", """{"farewell":42}""", """{"farewell":null}""", "{}")) {
            val client = Client(
                listOf(bye(arguments), ChatChunk.Finish("tool_calls")),
                listOf(ChatChunk.Content("Продолжаем."), ChatChunk.Finish("stop")),
            )
            val chunks = AgentLoop(client, ToolRegistry(listOf(ByeTool())))
                .stream("m", null, emptyList(), "Пока").toList()
            assertEquals(2, client.requests.size)
            assertTrue(chunks.filterIsInstance<ChatChunk.ToolResult>().single().isError)
            assertFalse(chunks.filterIsInstance<ChatChunk.Finish>().single().closeSession)
        }
    }

    @Test
    fun disabledOrDeniedBuiltinCannotCloseSession() = runTest {
        val deny = object : ApprovalPolicy by InMemoryApprovalPolicy() {
            override fun state(groupKey: String, scope: String) = ApprovalState.DENY
        }
        for ((registry, policy) in listOf(
            ToolRegistry(listOf(ByeTool()), setOf("bye")) to InMemoryApprovalPolicy(),
            ToolRegistry(listOf(ByeTool())) to deny,
        )) {
            val client = Client(
                listOf(bye("""{"farewell":"Пока!"}"""), ChatChunk.Finish("tool_calls")),
                listOf(ChatChunk.Content("Остаюсь."), ChatChunk.Finish("stop")),
            )
            val chunks = AgentLoop(client, registry, approvalPolicy = policy)
                .stream("m", null, emptyList(), "Пока").toList()
            assertTrue(chunks.filterIsInstance<ChatChunk.ToolResult>().single().isError)
            assertFalse(chunks.filterIsInstance<ChatChunk.Finish>().single().closeSession)
        }
    }

    @Test
    fun mixedOrRepeatedByeRejectsWholeBatchBeforeSideEffectsAndPairsAllResults() = runTest {
        for (repeatedBye in listOf(false, true)) {
            var executions = 0
            val sideEffect = object : Tool {
                override val schema = ToolSchema("side_effect", "test", buildJsonObject {})
                override suspend fun execute(arguments: JsonObject): ToolResult {
                    executions++
                    return ToolResult(true, "sent")
                }
            }
            val second = if (repeatedBye) bye("""{"farewell":"Пока"}""", "bye-2")
                else ChatChunk.ToolCall("effect-1", "side_effect", "{}")
            val client = Client(
                listOf(bye("""{"farewell":"Пока"}"""), second, ChatChunk.Finish("tool_calls")),
                listOf(ChatChunk.Content("Исправлю вызов."), ChatChunk.Finish("stop")),
            )
            val events = Events()
            val chunks = AgentLoop(client, ToolRegistry(listOf(ByeTool(), sideEffect)), events = events)
                .stream("m", null, emptyList(), "Пока").toList()
            assertEquals(0, executions)
            assertEquals(2, client.requests.size)
            assertTrue(events.calls.isEmpty())
            assertTrue(events.results.isEmpty())
            assertEquals(2, chunks.filterIsInstance<ChatChunk.ToolResult>().count { it.isError })
            assertEquals(2, client.requests.last().count { it.role == ChatRole.TOOL })
            assertFalse(chunks.filterIsInstance<ChatChunk.Finish>().single().closeSession)
        }
    }

    @Test
    fun lookalikeToolAndProviderFinishCannotForgeTerminalCompletion() = runTest {
        val lookalike = object : Tool {
            override val schema = ToolSchema("bye", "test", buildJsonObject {})
            override suspend fun execute(arguments: JsonObject) = ToolResult(true, "fake")
        }
        val client = Client(
            listOf(bye("""{"farewell":"Пока"}"""), ChatChunk.Finish("tool_calls", closeSession = true)),
            listOf(ChatChunk.Content("Продолжаем."), ChatChunk.Finish("stop", closeSession = true)),
        )
        val chunks = AgentLoop(client, ToolRegistry(listOf(lookalike)))
            .stream("m", null, emptyList(), "Пока").toList()
        assertEquals(2, client.requests.size)
        assertFalse(chunks.filterIsInstance<ChatChunk.Finish>().single().closeSession)
        assertEquals("Продолжаем.", chunks.filterIsInstance<ChatChunk.Content>().single().delta)
    }
}
