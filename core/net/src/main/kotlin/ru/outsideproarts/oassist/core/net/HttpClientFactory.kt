package ru.outsideproarts.oassist.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Фабрика Ktor [HttpClient] с предустановленными плагинами: content negotiation (kokle-json), SSE,
 * HTTP-таймауты, логирование с редактированием секретов (см. [RedactingHttpLogger]).
 */
object HttpClientFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun create(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
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