package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

    // Null session means no completed handshake; a null id inside Session is stateless.
    private data class Session(val id: String?, val version: String)
    private data class Exchange(val rpc: McpResponseDto?, val sessionId: String?)
    private val sessionMutex = Mutex()
    private var session: Session? = null

    suspend fun listTools(): List<McpToolInfo> {
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
        val params = McpJson.encodeToJsonElement(
            McpCallParamsDto.serializer(),
            McpCallParamsDto(name = name, arguments = arguments),
        )
        return call("tools/call", params, McpCallResultDto.serializer())
    }

    private suspend fun ensureSession(expired: Session? = null): Session = sessionMutex.withLock {
        if (expired != null && session === expired) session = null
        session?.let { return@withLock it }
        val params = McpJson.encodeToJsonElement(
            McpInitializeParamsDto.serializer(),
            McpInitializeParamsDto(
                protocolVersion = PROTOCOL_VERSION,
                clientInfo = McpClientInfoDto(name = CLIENT_NAME, version = CLIENT_VERSION),
            ),
        )
        val initialized = doPost("initialize", params, null)
        val result = decodeResult("initialize", initialized.rpc!!, McpInitializeResultDto.serializer())
        if (result.protocolVersion != PROTOCOL_VERSION) {
            throw McpError("MCP initialize: неподдерживаемая версия протокола ${result.protocolVersion?.take(ERROR_LIMIT) ?: "(отсутствует)"}; поддерживается $PROTOCOL_VERSION")
        }
        val candidate = Session(initialized.sessionId, result.protocolVersion)
        doPost("notifications/initialized", buildJsonObject {}, candidate, notification = true)
        currentCoroutineContext().ensureActive()
        session = candidate
        candidate
    }

    private suspend fun <T> call(
        method: String,
        params: JsonElement,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val active = ensureSession()
        val rpc = try {
            doPost(method, params, active).rpc!!
        } catch (e: ClientRequestException) {
            // Only a 404 for an assigned session permits replay. A late 404 must
            // reuse a replacement session, never invalidate that newer handshake.
            if (e.response.status.value != 404 || active.id == null) throw e
            doPost(method, params, ensureSession(expired = active)).rpc!!
        }
        return decodeResult(method, rpc, deserializer)
    }

    private fun <T> decodeResult(
        method: String,
        rpc: McpResponseDto,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        rpc.error?.let { throw McpError("MCP $method: ${it.message.take(ERROR_LIMIT)}") }
        val result = rpc.result ?: throw McpError("MCP $method: пустой result")
        return runCatching { McpJson.decodeFromJsonElement(deserializer, result) }
            .getOrElse { throw McpError("MCP $method: непонятный result", it) }
    }

    private suspend fun doPost(
        method: String,
        params: JsonElement,
        active: Session?,
        notification: Boolean = false,
    ): Exchange {
        val operation = Diagnostics.newOperationId()
        val parent = currentCoroutineContext()[DiagnosticContext]?.operationId
        val secrets = diagnosticSecrets()
        diagnostics.emit(DiagnosticKind.OPERATION_START, operation, parentOperationId = parent,
            attributes = mapOf("operation" to "mcp", "method" to method),
            content = params.toString(), secrets = secrets)
        try {
            val requestId = if (notification) null else nextId()
            val requestBody = if (notification) {
                McpJson.encodeToString(McpNotificationDto.serializer(), McpNotificationDto(method = method, params = params))
            } else {
                McpJson.encodeToString(McpRequestDto.serializer(), McpRequestDto(id = requestId!!, method = method, params = params))
            }
            return http.preparePost(url) {
                expectSuccess = false
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
                    this@McpClient.headers.forEach { (name, value) -> append(name, value) }
                    // Session/protocol are transport-owned, even when custom headers contain them.
                    remove(SESSION_HEADER)
                    remove(PROTOCOL_HEADER)
                    active?.id?.let { append(SESSION_HEADER, it) }
                    active?.version?.let { append(PROTOCOL_HEADER, it) }
                }
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                if (httpResponse.status.value >= 400) throw ClientRequestException(httpResponse, "MCP HTTP ${httpResponse.status.value}")
                if (httpResponse.status.value !in 200..299) throw McpError("MCP $method: HTTP ${httpResponse.status.value}")
                val parsed = if (notification) {
                    if (httpResponse.status.value != 202 || httpResponse.bodyAsText().isNotEmpty()) {
                        throw McpError("MCP $method: ожидался HTTP 202 без тела")
                    }
                    null
                } else if (httpResponse.contentType()?.withoutParameters() == ContentType.Text.EventStream) {
                    val parser = SseResponseParser(method, requestId!!)
                    val channel = httpResponse.bodyAsChannel()
                    var matched: McpResponseDto? = null
                    while (matched == null) {
                        val line = channel.readUTF8Line() ?: break
                        matched = parser.line(line)
                    }
                    matched ?: throw McpError("MCP $method: SSE завершён без ответа на запрос")
                } else {
                    parseRpcBody(httpResponse.bodyAsText(), httpResponse.headers[HttpHeaders.ContentType], method, requestId!!)
                }
                currentCoroutineContext().ensureActive()
                diagnostics.emit(DiagnosticKind.OPERATION_END, operation,
                    attributes = mapOf("status" to httpResponse.status.value.toString(), "method" to method),
                    content = parsed?.let { McpJson.encodeToString(McpResponseDto.serializer(), it) }, secrets = secrets)
                Exchange(parsed, httpResponse.headers[SESSION_HEADER])
            }
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
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "oassist"
        const val CLIENT_VERSION = "1"

        private const val ACCEPT_JSON = "application/json"
        private const val ACCEPT_SSE = "text/event-stream"
        private const val ERROR_LIMIT = 200

        private val idCounter = java.util.concurrent.atomic.AtomicLong(1)
        private fun nextId(): Long = idCounter.getAndIncrement()

        internal fun parseRpcBody(body: String, contentType: String?, method: String, expectedId: Long): McpResponseDto {
            if (contentType?.substringBefore(';')?.trim()?.equals(ACCEPT_SSE, true) == true ||
                body.lineSequence().any { it.startsWith("data:") || it.startsWith("event:") }
            ) {
                val parser = SseResponseParser(method, expectedId)
                for (line in body.reader().readLines()) parser.line(line)?.let { return it }
                throw McpError("MCP $method: SSE завершён без ответа на запрос")
            }
            return matchingResponse(body, method, expectedId)
                ?: throw McpError("MCP $method: ответ не соответствует id запроса")
        }

        private fun matchingResponse(json: String, method: String, expectedId: Long): McpResponseDto? {
            val message = runCatching { McpJson.parseToJsonElement(json).jsonObject }
                .getOrElse { throw McpError("MCP $method: не JSON-RPC ответ", it) }
            // Notifications, server requests and unrelated responses cannot satisfy this call.
            if ("method" in message || message["id"] != JsonPrimitive(expectedId)) return null
            if (message["jsonrpc"] != JsonPrimitive("2.0") ||
                (("result" in message) == ("error" in message))
            ) throw McpError("MCP $method: некорректный JSON-RPC ответ")
            return runCatching { McpJson.decodeFromJsonElement(McpResponseDto.serializer(), message) }
                .getOrElse { throw McpError("MCP $method: не JSON-RPC ответ", it) }
        }

        private class SseResponseParser(private val method: String, private val expectedId: Long) {
            private val data = mutableListOf<String>()
            private var firstLine = true

            fun line(raw: String): McpResponseDto? {
                val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
                firstLine = false
                if (line.isEmpty()) {
                    if (data.isEmpty()) return null
                    val payload = data.joinToString("\n")
                    data.clear()
                    return matchingResponse(payload, method, expectedId)
                }
                val field = line.substringBefore(':')
                if (field == "data") data += line.substringAfter(':', "").removePrefix(" ")
                return null
            }
        }
    }
}
