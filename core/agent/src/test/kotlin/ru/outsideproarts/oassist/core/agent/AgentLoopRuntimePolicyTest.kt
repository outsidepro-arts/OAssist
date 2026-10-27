package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopRuntimePolicyTest {

    private class PingTool : Tool {
        override val groupKey = ToolGroups.SERVICE
        override val schema = ToolSchema(
            name = "ping",
            description = "test service tool",
            parameters = buildJsonObject { put("type", "object") },
        )

        override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult(true, "pong")
    }

    private class RetryAndToolRoundClient : ModelClient {
        val requests = mutableListOf<List<ChatMessage>>()
        private var calls = 0

        override suspend fun fetchModels(): List<ModelInfo> = emptyList()

        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            requests += messages
            calls++
            return when (calls) {
                1 -> flow { throw ProviderError.Network("temporary test failure") }
                2 -> flowOf(
                    ChatChunk.ToolCall(id = "call-1", name = "ping", argumentsJson = "{}"),
                    ChatChunk.Finish("tool_calls"),
                )
                else -> flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
            }
        }
    }

    @Test
    fun runtimePolicyIsAddedOnceForEveryModeAndDoesNotChangeOwnerPromptsOrPersist() = runTest {
        val ownerPrompts = listOf(
            null,
            "",
            "Custom owner prompt.",
            "Legacy default: dangerous actions only after confirmation.",
            "Когда пользователь прощается или благодарит - коротко попрощайся и вызови инструмент bye.",
            "Before every call, ask me separately in chat.",
        )
        val scopes = listOf("CHAT", "ASSISTANT", "SHARE")

        for (scope in scopes) {
            for (ownerPrompt in ownerPrompts) {
                val client = RetryAndToolRoundClient()
                var persistedTurn: List<ChatMessage>? = null
                val agent = AgentLoop(
                    modelClient = client,
                    toolRegistry = ToolRegistry(listOf(PingTool())),
                    sessionScope = scope,
                )

                agent.stream(
                    modelId = "test",
                    systemPrompt = ownerPrompt,
                    history = emptyList(),
                    userMessage = "Call the service.",
                    onTurnMessages = { persistedTurn = it },
                ).toList()

                assertEquals("$scope / $ownerPrompt: one failed attempt, retry and tool round", 3, client.requests.size)
                val systems = client.requests.map { messages ->
                    messages.single { it.role == ChatRole.SYSTEM }.content
                }
                systems.forEachIndexed { attempt, system ->
                    assertEquals("$scope / $ownerPrompt / attempt $attempt", 1, system.occurrences(RUNTIME_TRUST_POLICY))
                    assertTrue(system.contains("при самостоятельной благодарности без новой просьбы и незавершённой задачи"))
                    assertTrue(system.contains("Благодарность с продолжением запроса не завершает диалог"))
                    if (!ownerPrompt.isNullOrBlank()) {
                        assertTrue("$scope / owner prompt was changed", system.contains(ownerPrompt))
                        assertTrue("$scope / app policy must precede owner prompt", system.indexOf(RUNTIME_TRUST_POLICY) < system.indexOf(ownerPrompt))
                    }
                }
                assertEquals("early retry must resend the same prompt", systems[0], systems[1])
                assertEquals("tool round must not wrap or grow the prompt", systems[1], systems[2])

                val turn = requireNotNull(persistedTurn)
                assertTrue(turn.isNotEmpty())
                assertFalse(turn.any { it.role == ChatRole.SYSTEM })
                assertTrue(turn.none { it.content.contains(RUNTIME_TRUST_POLICY) })
            }
        }
    }

    private fun String.occurrences(value: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = indexOf(value, offset)
            if (found < 0) return count
            count++
            offset = found + value.length
        }
    }
}
