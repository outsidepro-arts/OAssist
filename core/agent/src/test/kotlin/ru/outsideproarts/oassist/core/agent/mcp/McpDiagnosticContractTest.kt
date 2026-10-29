package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.*

class McpDiagnosticContractTest {
    private val token = "bare-mcp-token-42"
    private val basic = "dXNlcjpwYXNzd29yZA=="

    private class Recorder : DiagnosticRecorder {
        val events = mutableListOf<Pair<DiagnosticEvent, Collection<String>>>()
        override fun record(event: DiagnosticEvent, secrets: Collection<String>) {
            events += event to secrets.toList()
        }
        override fun health() = DiagnosticHealth(true, true, 0, 0)
    }

    @Test fun mcpEmitsCorrelatedEventsAndSuppliesCredentialsForRawPayloads() = runBlocking {
        val recorder = Recorder()
        var count = 0
        val engine = MockEngine {
            count++
            val body = when (count) {
                1, 2 -> """{"jsonrpc":"2.0","id":1,"result":{}}"""
                3 -> """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"$token $basic"}]}}"""
                else -> """{"jsonrpc":"2.0","id":4,"error":{"code":-1,"message":"$token $basic"}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(
                "Content-Type" to listOf("application/json"),
                McpClient.SESSION_HEADER to listOf("session"),
            ))
        }
        val client = McpClient(recorder, HttpClient(engine), "https://mcp.example.com", mapOf(
            "authorization" to "Bearer $token", "Proxy-Authorization" to "Basic $basic",
        ))
        withContext(DiagnosticContext("parent")) {
            client.callTool("read", buildJsonObject { put("query", token) })
            try { client.callTool("read", JsonObject(emptyMap())); fail("expected MCP error") }
            catch (_: McpError) { }
        }
        val starts = recorder.events.filter { it.first.kind == DiagnosticKind.OPERATION_START }
        val ends = recorder.events.filter { it.first.kind == DiagnosticKind.OPERATION_END }
        assertEquals(4, starts.size)
        assertEquals(4, ends.size)
        assertTrue(starts.all { it.first.parentOperationId == "parent" })
        assertEquals(starts.map { it.first.operationId }.toSet(), ends.map { it.first.operationId }.toSet())
        val request = starts.single { it.first.content?.contains(token) == true }
        val responses = ends.filter { it.first.content?.contains(token) == true }
        assertEquals(2, responses.size)
        (listOf(request) + responses).forEach { (event, secrets) ->
            val cleaned = secrets.fold(event.content!!) { text, secret -> text.replace(secret, "[REDACTED]") }
            assertFalse(cleaned.contains(token))
            assertFalse(cleaned.contains(basic))
        }
        assertTrue(recorder.events.all { (_, secrets) -> token in secrets && basic in secrets })
    }

    @Test fun mcpEmitsErrorAndCancellationWithMatchingOperations() = runBlocking {
        for (cancel in listOf(false, true)) {
            val recorder = Recorder()
            val engine = MockEngine { if (cancel) throw CancellationException("cancel") else throw IllegalStateException("failure") }
            val client = McpClient(recorder, HttpClient(engine), "https://mcp.example.com", mapOf("Authorization" to "Bearer $token"))
            try { withContext(DiagnosticContext("parent")) { client.listTools() }; fail("expected failure") }
            catch (_: CancellationException) { if (!cancel) fail("unexpected cancellation") }
            catch (_: IllegalStateException) { if (cancel) fail("unexpected error") }
            val start = recorder.events.single { it.first.kind == DiagnosticKind.OPERATION_START }.first
            val terminal = recorder.events.single { it.first.kind == if (cancel) DiagnosticKind.OPERATION_CANCELLED else DiagnosticKind.OPERATION_ERROR }
            assertEquals("parent", start.parentOperationId)
            assertEquals(start.operationId, terminal.first.operationId)
            assertTrue(token in terminal.second)
        }
    }
}
