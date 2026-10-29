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
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.model.diagnostics.emit
import kotlinx.coroutines.currentCoroutineContext

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
    private val diagnostics: DiagnosticRecorder,
    private val http: HttpClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) {
    internal fun diagnosticSecrets(): Collection<String> = headers.flatMap { (name, value) ->
        val credentials = if (name.equals(HttpHeaders.Authorization, ignoreCase = true) ||
            name.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true)
        ) {
            val parts = value.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && (parts[0].equals("Bearer", ignoreCase = true) ||
                    parts[0].equals("Basic", ignoreCase = true))) listOf(parts[1]) else emptyList()
        } else emptyList()
        listOf(value) + credentials
    }

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
        // Отмену не глотаем (иначе «Стоп» во время handshake залипает).
        try {
            postRpc("notifications/initialized", buildJsonObject {})
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
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
        try {
            return doPost(method, params)
        } catch (e: io.ktor.client.plugins.ResponseException) {
            // Сервер забыл сессию (рестарт/эвикция): сбрасываем id, проходим
            // handshake заново и повторяем вызов один раз. Чужие 404
            // (неверный URL) — мимо: sessionId там null, пробрасываем как есть.
            if (e.response.status != io.ktor.http.HttpStatusCode.NotFound || sessionId == null) throw e
            sessionId = null
            ensureSession()
            return doPost(method, params)
        }
    }

    private suspend fun doPost(method: String, params: JsonElement): McpResponseDto {
        val operation = Diagnostics.newOperationId()
        val parent = currentCoroutineContext()[DiagnosticContext]?.operationId
        val secrets = diagnosticSecrets()
        diagnostics.emit(DiagnosticKind.OPERATION_START, operation, parentOperationId = parent,
            attributes = mapOf("operation" to "mcp", "method" to method),
            content = params.toString(), secrets = secrets)
        try {
        val request = McpRequestDto(id = nextId(), method = method, params = params)
        val httpResponse = http.post(url) {
            attributes.put(ru.outsideproarts.oassist.core.net.DiagnosticOperationKey, operation)
            attributes.put(ru.outsideproarts.oassist.core.net.DiagnosticAttemptKey, java.util.concurrent.atomic.AtomicInteger())
            // tools/call может иметь сайд-эффект (отправить/создать): авто-повтор
            // задвоит действие, если запрос дошёл, а ответ потерялся. initialize
            // и tools/list идемпотентны — им повтор HttpRequestRetry полезен.
            if (method == "tools/call") {
                attributes.put(ru.outsideproarts.oassist.core.net.NoAutoRetry, Unit)
            }
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
        val parsed = parseRpcBody(httpResponse.bodyAsText(), contentType, method)
        diagnostics.emit(DiagnosticKind.OPERATION_END, operation,
            attributes = mapOf("status" to httpResponse.status.value.toString(), "method" to method),
            content = McpJson.encodeToString(McpResponseDto.serializer(), parsed), secrets = secrets)
        return parsed
        } catch (e: kotlinx.coroutines.CancellationException) {
            diagnostics.emit(DiagnosticKind.OPERATION_CANCELLED, operation, secrets = secrets)
            throw e
        } catch (e: Throwable) {
            diagnostics.emit(DiagnosticKind.OPERATION_ERROR, operation,
                attributes = mapOf("error" to e.javaClass.simpleName, "method" to method), secrets = secrets)
            throw e
        }
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
