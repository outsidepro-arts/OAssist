package ru.outsideproarts.oassist.core.agent.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-контракт JSON-RPC 2.0: поля со значениями-равными-дефолту
 * (`jsonrpc`, `capabilities`) обязаны уходить на сервер — строгие
 * серверы (FastMCP и др.) отвечают 400 Validation error без них.
 * MockEngine-тесты не ловят этот класс бага — они не смотрят в тело запроса.
 */
class McpRequestEncodingTest {

    @Test
    fun request_writesJsonrpcVersion() {
        val json = McpJson.encodeToString(
            McpRequestDto.serializer(),
            McpRequestDto(id = 1, method = "initialize", params = null),
        )
        assertTrue(json, json.contains(""""jsonrpc":"2.0""""))
        assertTrue(json, json.contains(""""method":"initialize""""))
    }

    @Test
    fun request_nullParamsNotWritten() {
        val json = McpJson.encodeToString(
            McpRequestDto.serializer(),
            McpRequestDto(id = 1, method = "notifications/initialized", params = null),
        )
        assertFalse(json, json.contains("params"))
    }

    @Test
    fun initializeParams_writeEmptyCapabilities() {
        val json = McpJson.encodeToString(
            McpInitializeParamsDto.serializer(),
            McpInitializeParamsDto(
                protocolVersion = "2025-06-18",
                clientInfo = McpClientInfoDto(name = "oassist", version = "1"),
            ),
        )
        assertTrue(json, json.contains(""""capabilities":{}"""))
        assertTrue(json, json.contains(""""protocolVersion":"2025-06-18""""))
    }
}
