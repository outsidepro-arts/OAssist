package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolGroups

class McpClientTest {

    /** Сервер по порядку вызовов: initialize → initialized → tools/list → tools/call. */
    private fun scriptedEngine(): MockEngine {
        var calls = 0
        return MockEngine {
            calls++
            val body = when (calls) {
                1 -> """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"t","version":"1"}}}"""
                3 -> """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"read","description":"Read file","inputSchema":{"type":"object"}}]}}"""
                4 -> """{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"file-bytes"}],"isError":false}}"""
                else -> """{"jsonrpc":"2.0","id":$calls,"result":{}}"""
            }
            val headers = if (calls == 1) {
                headersOf(
                    "Content-Type" to listOf("application/json"),
                    McpClient.SESSION_HEADER to listOf("sess-1"),
                )
            } else {
                headersOf("Content-Type" to listOf("application/json"))
            }
            respond(body, HttpStatusCode.OK, headers)
        }
    }

    private fun client(engine: MockEngine) =
        McpClient(HttpClient(engine) { expectSuccess = true }, "https://mcp.example.com/mcp")

    @Test
    fun listTools_handshakesAndLists() = runTest {
        val tools = client(scriptedEngine()).listTools()
        assertEquals(1, tools.size)
        assertEquals("read", tools.single().name)
        assertEquals("Read file", tools.single().description)
        assertEquals("object", tools.single().inputSchema["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun callTool_returnsText() = runTest {
        val mcp = client(scriptedEngine())
        mcp.listTools()
        val result = mcp.callTool("read", JsonObject(emptyMap()))
        assertFalse(result.isError)
        assertEquals("file-bytes", result.content.single().text)
    }

    @Test
    fun toolAdapter_isAskAndExecutes() = runBlocking {
        // runBlocking, а не runTest: execute() обёрнут в withTimeoutOrNull —
        // в runTest виртуальный таймер обгоняет ответ MockEngine с реального
        // диспетчера и тест ложно «таймаутится».
        val mcp = client(scriptedEngine())
        val info = mcp.listTools().single()
        val tool = McpTool(
            serverId = "srv-1",
            serverName = "Домашний",
            toolName = info.name,
            displayName = McpTool.displayName("srv-1", info.name),
            description = info.description,
            parameters = info.inputSchema,
            client = mcp,
        )
        assertEquals(ToolGroups.mcp("srv-1"), tool.groupKey)
        assertTrue(tool.schema.description!!.contains("Домашний"))
        val out = tool.execute(JsonObject(emptyMap()))
        assertTrue(out.success)
        assertEquals("file-bytes", out.output)
    }

    @Test
    fun toolName_hasServerPrefix() {
        assertEquals("mcp_srv-1__read", McpTool.displayName("srv-1", "read"))
    }

    @Test
    fun errorResult_mapsToFailure() = runBlocking {
        // runBlocking — см. комментарий в toolAdapter_isAskAndExecutes.
        var calls = 0
        val engine = MockEngine {
            calls++
            val body = when (calls) {
                1 -> """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
                3 -> """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"boom","inputSchema":{"type":"object"}}]}}"""
                else -> """{"jsonrpc":"2.0","id":$calls,"result":{"content":[{"type":"text","text":"denied"}],"isError":true}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        val mcp = client(engine)
        val info = mcp.listTools().single()
        val tool = McpTool("s", "S", info.name, McpTool.displayName("s", info.name), null, info.inputSchema, mcp)
        val out = tool.execute(JsonObject(emptyMap()))
        assertFalse(out.success)
        assertEquals("denied", out.output)
    }

    @Test
    fun callTool_sessionLost_reinitializesAndRetriesOnce() = runTest {
        // Сервер забыл сессию (рестарт): 404 на tools/call → новый handshake → повтор вызова.
        var calls = 0
        val engine = MockEngine {
            calls++
            when (calls) {
                1 -> respond(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
                    HttpStatusCode.OK,
                    headersOf(
                        "Content-Type" to listOf("application/json"),
                        McpClient.SESSION_HEADER to listOf("sess-1"),
                    ),
                )
                2 -> respond(
                    """{"jsonrpc":"2.0","id":2,"result":{}}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type" to listOf("application/json")),
                )
                3 -> respond("gone", HttpStatusCode.NotFound)
                4 -> respond(
                    """{"jsonrpc":"2.0","id":4,"result":{"protocolVersion":"2025-06-18"}}""",
                    HttpStatusCode.OK,
                    headersOf(
                        "Content-Type" to listOf("application/json"),
                        McpClient.SESSION_HEADER to listOf("sess-2"),
                    ),
                )
                5 -> respond(
                    """{"jsonrpc":"2.0","id":5,"result":{}}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type" to listOf("application/json")),
                )
                else -> respond(
                    """{"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"file-bytes"}],"isError":false}}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type" to listOf("application/json")),
                )
            }
        }
        val mcp = client(engine)
        val result = mcp.callTool("read", JsonObject(emptyMap()))
        assertFalse(result.isError)
        assertEquals("file-bytes", result.content.single().text)
        // initialize + initialized + call(404) + re-initialize + initialized + call(ок).
        assertEquals(6, calls)
    }

    @Test
    fun execute_hangingServer_returnsTimeoutError() = runTest {
        // Висящий сервер: маленький таймаут детерминированно стреляет
        // на виртуальном времени (единственное отложенное событие).
        val hanging = MockEngine { awaitCancellation() }
        val mcp = McpClient(HttpClient(hanging) { expectSuccess = true }, "https://mcp.example.com/mcp")
        val tool = McpTool(
            serverId = "s",
            serverName = "S",
            toolName = "read",
            displayName = McpTool.displayName("s", "read"),
            description = null,
            parameters = JsonObject(emptyMap()),
            client = mcp,
            callTimeoutMs = 100,
        )
        val out = tool.execute(JsonObject(emptyMap()))
        assertFalse(out.success)
        assertTrue(out.output.contains("таймаут"))
    }

    @Test
    fun callTool_setsNoAutoRetry_onlyForToolsCall() = runBlocking {
        // tools/call может иметь сайд-эффект: авто-повтор HttpRequestRetry должен
        // быть подавлен. initialize/tools/list — идемпотентны, атрибут не ставим.
        val flags = mutableListOf<Boolean>()
        val engine = MockEngine { request ->
            flags.add(
                request.attributes.contains(ru.outsideproarts.oassist.core.net.NoAutoRetry),
            )
            respond(
                """{"jsonrpc":"2.0","id":1,"result":{}}""",
                HttpStatusCode.OK,
                headersOf(
                    "Content-Type" to listOf("application/json"),
                    // Сессия закрепляется за первым ответом — иначе ensureSession
                    // перед каждым RPC пошёл бы в повторный handshake.
                    McpClient.SESSION_HEADER to listOf("sess-1"),
                ),
            )
        }
        val mcp = client(engine)
        mcp.listTools()
        mcp.callTool("read", JsonObject(emptyMap()))
        // initialize + initialized + tools/list + tools/call.
        assertEquals(listOf(false, false, false, true), flags)
    }
}
