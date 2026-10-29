package ru.outsideproarts.oassist.core.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import java.io.IOException
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import java.util.concurrent.atomic.AtomicInteger
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder
import ru.outsideproarts.oassist.core.model.diagnostics.emit

/**
 * Атрибут-опт-аут авто-повторов [HttpRequestRetry]: запрос уже мог дойти до
 * сервера (сайд-эффект выполнился), повтор задвоит действие. Ставится на
 * `tools/call` MCP (`initialize`/`tools/list` — идемпотентны, им повтор полезен).
 */
val NoAutoRetry: AttributeKey<Unit> = AttributeKey("OAssistNoAutoRetry")
val DiagnosticOperationKey: AttributeKey<String> = AttributeKey("OAssistDiagnosticOperation")
val DiagnosticAttemptKey: AttributeKey<java.util.concurrent.atomic.AtomicInteger> = AttributeKey("OAssistDiagnosticAttempt")

/** Статусы, при которых запрос безопасно повторить: 5xx, 429, 408. */
internal fun isRetryableStatus(status: Int): Boolean =
    status in 500..599 || status == 429 || status == 408

/** Повторов запроса после первой попытки (экспоненциальная задержка с джиттером). */
private const val RETRY_MAX = 2

/**
 * Фабрика Ktor [HttpClient] с предустановленными плагинами: content negotiation (kokle-json), SSE,
 * HTTP-таймауты и обязательная структурированная диагностика.
 */
/**
 * JSON wire-сериализации тел запросов: используется ContentNegotiation фабрики
 * и мержом дополнительных полей тела (`extraBody` в core:provider). Конфиг
 * обязан быть единым — иначе тела запросов с дополнениями разойдутся с обычными.
 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

object HttpClientFactory {

    fun create(diagnostics: DiagnosticRecorder): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(WireJson) }
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
        installRetryPolicy(diagnostics)
        defaultRequest {
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        expectSuccess = true
    }
}

/**
 * Политика повторов запроса. Отдельная функция, чтобы юнит-тест воспроизводил её
 * на MockEngine с тем же порядком конфигурации, что и прод (`expectSuccess`
 * после установки — дефолтный валидатор регистрируется после плагинов блока).
 */
internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installRetryPolicy(
    diagnostics: DiagnosticRecorder,
    maxRetries: Int = RETRY_MAX,
    maxDelayMs: Long = 60_000,
    randomizationMs: Long = 1_000,
) {
    install(HttpRequestRetry) {
        // Повторы — только до первого байта ответа (handshake, GET, POST
        // с телом в памяти): 5xx/429/408 и сетевые обрывы на установке соединения.
        // Обрыв посреди SSE-стрима сюда не доходит (тело читается позже,
        // вне Send-перехватчика) — им занимается StreamRetry в core:provider.
        // SSE-handshake ретраится тоже: токенов ещё нет, сайд-эффектов нет.
        retryIf(maxRetries) { request, response ->
            !request.attributes.contains(NoAutoRetry) && isRetryableStatus(response.status.value)
        }
        // expectSuccess=true: валидатор Ktor сидит ВНУТРИ этого хука (первый
        // зарегистрированный Send-хук — внешний, дефолты регистрируются после
        // плагинов блока) и превращает 5xx в исключение до того, как retryIf
        // увидит ответ. Поэтому ретрай по статусу дублируем в exception-ветке —
        // ветки взаимоисключающи (на попытку — либо ответ, либо исключение),
        // двойного счёта попыток не будет.
        retryOnExceptionIf(maxRetries) { request, cause ->
            !request.attributes.contains(NoAutoRetry) && shouldRetryDiagnosticFailure(cause)
        }
        exponentialDelay(maxDelayMs = maxDelayMs, randomizationMs = randomizationMs)
    }
    install(DiagnosticHttpAttempts) {
        recorder = diagnostics
        retryLimit = maxRetries
    }

}

private fun shouldRetryDiagnosticFailure(cause: Throwable): Boolean = when (cause) {
    is CancellationException -> false
    is IOException -> true
    is ResponseException -> isRetryableStatus(cause.response.status.value)
    else -> false
}

private class DiagnosticHttpConfig {
    lateinit var recorder: DiagnosticRecorder
    var retryLimit: Int = RETRY_MAX
}

private val DiagnosticHttpAttempts = createClientPlugin("OAssistDiagnosticHttpAttempts", ::DiagnosticHttpConfig) {
    val recorder = pluginConfig.recorder
    val retryLimit = pluginConfig.retryLimit
    onRequest { request, _ ->
        if (!request.attributes.contains(DiagnosticAttemptKey)) {
            request.attributes.put(DiagnosticAttemptKey, AtomicInteger())
        }
    }
    on(Send) { request ->
        val counter = request.attributes.getOrNull(DiagnosticAttemptKey) ?: AtomicInteger().also {
            request.attributes.put(DiagnosticAttemptKey, it)
        }
        val attempt = counter.incrementAndGet()
        val operation = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.newOperationId()
        val parent = request.attributes.getOrNull(DiagnosticOperationKey)
            ?: currentCoroutineContext()[DiagnosticContext]?.operationId
        val attributes = mapOf("attempt" to attempt.toString(), "method" to request.method.value)
        recorder.emit(DiagnosticKind.HTTP_REQUEST, operation, parent, attributes)
        try {
            proceed(request).also { call ->
                recorder.emit(DiagnosticKind.HTTP_RESPONSE, operation, parent,
                    attributes + mapOf("status" to call.response.status.value.toString(),
                        "retry" to (attempt <= retryLimit && !request.attributes.contains(NoAutoRetry) &&
                            isRetryableStatus(call.response.status.value)).toString()))
            }
        } catch (cause: Throwable) {
            recorder.emit(DiagnosticKind.HTTP_ERROR, operation, parent,
                attributes + mapOf("error" to cause.javaClass.simpleName,
                    "status" to ((cause as? ResponseException)?.response?.status?.value?.toString() ?: ""),
                    "cancelled" to (cause is CancellationException).toString(),
                    "retry" to (attempt <= retryLimit && !request.attributes.contains(NoAutoRetry) &&
                        shouldRetryDiagnosticFailure(cause)).toString()))
            throw cause
        }
    }
}
