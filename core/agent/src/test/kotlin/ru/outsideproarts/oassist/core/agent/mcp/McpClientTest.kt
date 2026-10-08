package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
    fun toolAdapter_isAskAndExecutes() = runTest {
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
    fun errorResult_mapsToFailure() = runTest {
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
}
