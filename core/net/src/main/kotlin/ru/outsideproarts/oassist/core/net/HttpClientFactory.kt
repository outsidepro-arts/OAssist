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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Атрибут-опт-аут авто-повторов [HttpRequestRetry]: запрос уже мог дойти до
 * сервера (сайд-эффект выполнился), повтор задвоит действие. Ставится на
 * `tools/call` MCP (`initialize`/`tools/list` — идемпотентны, им повтор полезен).
 */
val NoAutoRetry: AttributeKey<Unit> = AttributeKey("OAssistNoAutoRetry")

/** Статусы, при которых запрос безопасно повторить: 5xx, 429, 408. */
internal fun isRetryableStatus(status: Int): Boolean =
    status in 500..599 || status == 429 || status == 408

/** Повторов запроса после первой попытки (экспоненциальная задержка с джиттером). */
private const val RETRY_MAX = 2

/**
 * Фабрика Ktor [HttpClient] с предустановленными плагинами: content negotiation (kokle-json), SSE,
 * HTTP-таймауты, логирование с редактированием секретов (см. [RedactingHttpLogger]).
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

    fun create(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(WireJson) }
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
        installRetryPolicy()
        install(Logging) {
            level = LogLevel.HEADERS
            logger = RedactingHttpLogger()
        }
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
            !request.attributes.contains(NoAutoRetry) &&
                isRetryableStatus(response.status.value)
        }
        // expectSuccess=true: валидатор Ktor сидит ВНУТРИ этого хука (первый
        // зарегистрированный Send-хук — внешний, дефолты регистрируются после
        // плагинов блока) и превращает 5xx в исключение до того, как retryIf
        // увидит ответ. Поэтому ретрай по статусу дублируем в exception-ветке —
        // ветки взаимоисключающи (на попытку — либо ответ, либо исключение),
        // двойного счёта попыток не будет.
        retryOnExceptionIf(maxRetries) { request, cause ->
            if (request.attributes.contains(NoAutoRetry)) return@retryOnExceptionIf false
            when {
                cause is IOException -> true
                cause is ResponseException -> isRetryableStatus(cause.response.status.value)
                else -> false
            }
        }
        exponentialDelay(maxDelayMs = maxDelayMs, randomizationMs = randomizationMs)
    }
}