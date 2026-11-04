package ru.outsideproarts.oassist.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.mcp.McpClient
import ru.outsideproarts.oassist.core.agent.mcp.McpTool
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.speech.Narrator

class McpNarrationTest {
    @Test fun realNarratorReceivesFullReadableLabelsWhileProviderAndDispatchKeepIdentity() = runBlocking {
        for ((serverName, remoteName) in listOf(
            "Домашний сервер" to "прочитать_полное_содержимое_файла",
            "Home server" to "read_complete_file_contents",
        )) {
            val identity = McpTool.displayName("server-id", remoteName)
            var calledRemote: String? = null
            HttpClient(MockEngine { request ->
                val message = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val result = when (message["method"]!!.jsonPrimitive.content) {
                    "initialize" -> """{"protocolVersion":"2025-06-18"}"""
                    "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                    else -> {
                        calledRemote = message["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                        """{"content":[{"type":"text","text":"ok"}]}"""
                    }
                }
                respond("""{"jsonrpc":"2.0","id":${message["id"]},"result":$result}""",
                    headers = headersOf("Content-Type", "application/json"))
            }).use { http ->
                val tool = McpTool("server-id", serverName, remoteName, identity, null,
                    JsonObject(emptyMap()), McpClient(Diagnostics, http, "https://server.test"))
                val narrator = Narrator()
                val received = async(start = CoroutineStart.UNDISPATCHED) { narrator.events.take(2).toList() }
                val bridge = object : AgentEvents {
                    override suspend fun onToolCall(toolName: String, argumentsJson: String, summary: String) =
                        narrator.onToolCall(toolName, argumentsJson, summary)
                    override suspend fun onToolResult(toolName: String, output: String, success: Boolean) =
                        narrator.onToolResult(toolName, output, success)
                }
                val requests = mutableListOf<List<ChatMessage>>()
                val model = object : ModelClient {
                    override suspend fun fetchModels(): List<ModelInfo> = emptyList()
                    override fun chatCompletionStream(
                        modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>,
                        reasoningEnabled: Boolean,
                    ): Flow<ChatChunk> {
                        requests += messages.toList()
                        assertEquals(identity, tools.single().function.name)
                        return if (requests.size == 1) flowOf(
                            ChatChunk.ToolCall("call-1", identity, "{}"), ChatChunk.Finish("tool_calls"),
                        ) else flowOf(ChatChunk.Finish("stop"))
                    }
                }
                val policy = InMemoryApprovalPolicy().apply {
                    setRemembered(ToolGroups.mcp("server-id"), ApprovalState.ALLOW)
                }
                val chunks = AgentLoop(model, ToolRegistry(listOf(tool)), approvalPolicy = policy, events = bridge)
                    .stream("model", null, emptyList(), "read").toList()
                val events = received.await()
                val expectedLabel = "$serverName: $remoteName"
                assertEquals(expectedLabel, (events[0] as Narrator.NarrativeEvent.ToolCall).toolName)
                assertEquals(expectedLabel, (events[1] as Narrator.NarrativeEvent.ToolResult).toolName)
                assertFalse(events.toString().contains(identity))
                assertEquals(remoteName, calledRemote)
                assertEquals(identity, chunks.filterIsInstance<ChatChunk.ToolResult>().single().name)
                assertEquals(identity, requests.last().flatMap { it.toolCalls.orEmpty() }.single().name)
            }
        }
        // Built-ins still reach the app's RU/EN string-resource lookup by their existing key.
        assertEquals("bye", ByeTool().narrationName)
    }
}
