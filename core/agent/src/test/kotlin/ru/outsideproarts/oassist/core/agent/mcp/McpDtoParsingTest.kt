package ru.outsideproarts.oassist.core.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDtoParsingTest {

    @Test
    fun plainJson_parsesResult() {
        val body = McpClient.parseRpcBody(
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"read","description":"Read","inputSchema":{"type":"object"}}]}}""",
            "application/json",
            "tools/list",
        )
        val result = McpJson.decodeFromJsonElement(McpToolsListResultDto.serializer(), body.result!!)
        assertEquals("read", result.tools.single().name)
    }

    @Test
    fun sseWrapped_parsesLastData() {
        val body = McpClient.parseRpcBody(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}\n\n",
            "text/event-stream",
            "tools/list",
        )
        val result = McpJson.decodeFromJsonElement(McpToolsListResultDto.serializer(), body.result!!)
        assertTrue(result.tools.isEmpty())
    }

    @Test
    fun sseDetected_byBodyShape() {
        val body = McpClient.parseRpcBody(
            "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}",
            null,
            "tools/call",
        )
        val result = McpJson.decodeFromJsonElement(McpCallResultDto.serializer(), body.result!!)
        assertEquals("hi", result.content.single().text)
    }

    @Test
    fun jsonRpcError_parsesErrorField() {
        val body = McpClient.parseRpcBody(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
            "application/json",
            "tools/list",
        )
        assertEquals(-32601, body.error!!.code)
        assertEquals("Method not found", body.error.message)
    }

    @Test(expected = McpError::class)
    fun malformed_throwsMcpError() {
        McpClient.parseRpcBody("not-json-{", "application/json", "tools/list")
    }

    @Test(expected = McpError::class)
    fun emptySse_throwsMcpError() {
        McpClient.parseRpcBody("event: ping\n\n", "text/event-stream", "tools/list")
    }
}
