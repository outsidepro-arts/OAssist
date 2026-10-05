package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Ошибка MCP-сервера: человекочитаемый текст без тел ответов целиком
 * (длина режется) — уходит в ленту и скринридер.
 */
class McpError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Описание внешнего инструмента для адаптера [McpTool]. */
data class McpToolInfo(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
)

/**
 * Клиент MCP по Streamable HTTP: `initialize` → `tools/list` → `tools/call`.
 * Stateless-серверы (без `mcp-session-id`) тоже поддерживаются.
 *
 * [headers] — готовые заголовки (Bearer уже резолвлен вызывающим,
 * см. `McpServerRepository.resolveHeaders`): клиент секретов не касается.
 */
class McpClient(
    private val http: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) {

    /** Сессия Streamable HTTP: null — stateless-сервер. */
    private var sessionId: String? = null

    suspend fun listTools(): List<McpToolInfo> {
        ensureSession()
        val result = call("tools/list", buildJsonObject {}, McpToolsListResultDto.serializer())
        return result.tools.map { tool ->
            McpToolInfo(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema ?: JsonObject(emptyMap()),
            )
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResultDto {
        ensureSession()
        val params = McpJson.encodeToJsonElement(
            McpCallParamsDto.serializer(),
            McpCallParamsDto(name = name, arguments = arguments),
        )
        return call("tools/call", params, McpCallResultDto.serializer())
    }

    private suspend fun ensureSession() {
        if (sessionId != null) return
        val params = McpJson.encodeToJsonElement(
            McpInitializeParamsDto.serializer(),
            McpInitializeParamsDto(
                protocolVersion = PROTOCOL_VERSION,
                clientInfo = McpClientInfoDto(name = CLIENT_NAME, version = CLIENT_VERSION),
            ),
        )
        postRpc("initialize", params)
        // `notifications/initialized` — без ответа; 202/пустое тело — норма.
        runCatching { postRpc("notifications/initialized", buildJsonObject {}) }
    }

    private suspend fun <T> call(
        method: String,
        params: JsonElement,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val rpc = postRpc(method, params)
        rpc.error?.let { throw McpError("MCP $method: ${it.message.take(ERROR_LIMIT)}") }
        val result = rpc.result ?: throw McpError("MCP $method: пустой result")
        return runCatching { McpJson.decodeFromJsonElement(deserializer, result) }
            .getOrElse { throw McpError("MCP $method: непонятный result", it) }
    }

    private suspend fun postRpc(method: String, params: JsonElement): McpResponseDto {
        val request = McpRequestDto(id = nextId(), method = method, params = params)
        val httpResponse = http.post(url) {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append(HttpHeaders.Accept, "$ACCEPT_JSON, $ACCEPT_SSE")
                sessionId?.let { append(SESSION_HEADER, it) }
                this@McpClient.headers.forEach { (name, value) -> append(name, value) }
            }
            contentType(ContentType.Application.Json)
            setBody(McpJson.encodeToString(McpRequestDto.serializer(), request))
        }
        httpResponse.headers[SESSION_HEADER]?.let { sessionId = it }
        val contentType = httpResponse.headers[HttpHeaders.ContentType]
        return parseRpcBody(httpResponse.bodyAsText(), contentType, method)
    }

    companion object {
        const val SESSION_HEADER = "mcp-session-id"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "oassist"
        const val CLIENT_VERSION = "1"

        private const val ACCEPT_JSON = "application/json"
        private const val ACCEPT_SSE = "text/event-stream"
        private const val ERROR_LIMIT = 200

        private val idCounter = java.util.concurrent.atomic.AtomicLong(1)
        private fun nextId(): Long = idCounter.getAndIncrement()

        /**
         * Чистый парсер тела ответа: plain JSON-RPC или SSE-обёртка
         * (`data: {...}` строками). Тестируется без HTTP.
         */
        internal fun parseRpcBody(body: String, contentType: String?, method: String): McpResponseDto {
            val json = if (contentType?.contains(ACCEPT_SSE) == true || isSseBody(body)) {
                extractSseData(body) ?: throw McpError("MCP $method: пустой SSE-поток")
            } else {
                body
            }
            return runCatching { McpJson.decodeFromString(McpResponseDto.serializer(), json) }
                .getOrElse { throw McpError("MCP $method: не JSON-RPC ответ", it) }
        }

        private fun isSseBody(body: String): Boolean =
            body.lineSequence().any { it.startsWith("data:") || it.startsWith("event:") }

        private fun extractSseData(body: String): String? {
            val payloads = body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotEmpty() && it != "[DONE]" }
                .toList()
            // Для result-объектов берём последний data (финальный ответ).
            return payloads.lastOrNull()
        }
    }
}
