package ru.outsideproarts.oassist.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.nio.charset.Charset

/** Каким tier'ом добыт HTML: статикой или дорендером WebView. */
enum class FetchTier {
    STATIC,
    RENDERED,
}

/** Сырой результат HTTP-запроса до извлечения статьи. */
data class RawFetch(
    val finalUrl: String,
    val contentType: String?,
    val body: String,
    val bodyTruncated: Boolean,
)

/**
 * Статический tier чтения веба: GET через shared Ktor-клиент.
 * Без JS-рендера — для JS-страниц есть [WebRenderActions].
 */
class WebContentActions(private val httpClient: HttpClient) {

    suspend fun fetchStatic(rawUrl: String): Result<RawFetch> {
        val url = normalizeUrl(rawUrl)
            ?: return Result.failure(IllegalArgumentException("Некорректный URL: $rawUrl"))
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Некорректный URL: $rawUrl"))
        if (isBlockedHost(host)) {
            return Result.failure(SecurityException("Локальные адреса запрещены: $host"))
        }
        return runCatching {
            val response = withTimeoutOrNull(STATIC_TIMEOUT_MS) {
                httpClient.get(url) {
                    accept(ContentType.parse("text/markdown, text/html;q=0.9, text/plain;q=0.8"))
                }
            } ?: throw java.util.concurrent.TimeoutException("Таймаут $STATIC_TIMEOUT_MS мс: $url")
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_BODY_BYTES * 8) {
                throw IllegalStateException("Страница слишком большая (${contentLength / 1024 / 1024} МБ)")
            }
            val charset = responseCharset(response)
            val channel = response.bodyAsChannel()
            val (text, truncated) = readCapped(channel, charset, MAX_BODY_BYTES)
            RawFetch(
                finalUrl = response.request.url.toString(),
                contentType = response.contentType()?.toString(),
                body = text,
                bodyTruncated = truncated,
            )
        }
    }

    /**
     * Проба Markdown-варианта для док-сайтов (`url + .md`).
     * Только по уже деградировавшему пути: один лишний запрос допустим.
     */
    suspend fun fetchMarkdownVariant(url: String): Result<String> {
        val variant = url.trimEnd('/') + ".md"
        return runCatching {
            val response = withTimeoutOrNull(STATIC_TIMEOUT_MS) {
                httpClient.get(variant) {
                    accept(ContentType.parse("text/markdown"))
                }
            } ?: throw java.util.concurrent.TimeoutException("Таймаут: $variant")
            val type = response.contentType()?.toString() ?: ""
            if (!type.contains("markdown") && !type.contains("plain")) {
                throw IllegalStateException("Нет Markdown-варианта ($type)")
            }
            val text = readCapped(response.bodyAsChannel(), responseCharset(response), MAX_BODY_BYTES).text
            if (text.length < MIN_MARKDOWN_CHARS) throw IllegalStateException("Markdown-вариант пуст")
            text
        }
    }

    private data class CappedText(val text: String, val truncated: Boolean)

    /** Прочитать канал с капом: защита от гигантских страниц. */
    private suspend fun readCapped(
        channel: io.ktor.utils.io.ByteReadChannel,
        charset: Charset,
        capBytes: Int,
    ): CappedText {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        var truncated = false
        while (true) {
            if (total >= capBytes) {
                truncated = true
                break
            }
            val n = channel.readAvailable(buf, 0, minOf(buf.size, capBytes - total))
            if (n == -1) break
            out.write(buf, 0, n)
            total += n
        }
        return CappedText(out.toByteArray().toString(charset), truncated)
    }

    /** Charset из заголовка Content-Type, иначе UTF-8. */
    private fun responseCharset(response: io.ktor.client.statement.HttpResponse): Charset {
        val header = response.headers[io.ktor.http.HttpHeaders.ContentType] ?: return Charsets.UTF_8
        val name = header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim()
            .orEmpty()
        if (name.isEmpty()) return Charsets.UTF_8
        return runCatching { charset(name) }.getOrNull() ?: Charsets.UTF_8
    }

    companion object {
        const val MAX_BODY_BYTES = 262_144
        const val STATIC_TIMEOUT_MS = 15_000L
        private const val MIN_MARKDOWN_CHARS = 200
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return try {
                val uri = URI(withScheme)
                val scheme = uri.scheme?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                if (uri.host.isNullOrBlank()) return null
                withScheme
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Блок локальной сети: литеральные IP и localhost-имена.
         * Без DNS-резолва (резолв — уже сетевой выход); обход через DNS — вне threat model M2.6.
         */
        fun isBlockedHost(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            if (h == "localhost" || h.endsWith(".localhost")) return true
            if (h == "::1" || h == "[::1]") return true
            val v4 = h.split('.')
            if (v4.size == 4) {
                val octets = v4.map { it.toIntOrNull() ?: return false }
                if (octets.any { it !in 0..255 }) return false
                if (octets[0] == 127) return true
                if (octets[0] == 10) return true
                if (octets[0] == 172 && octets[1] in 16..31) return true
                if (octets[0] == 192 && octets[1] == 168) return true
                return false
            }
            return false
        }
    }
}

/**
 * Человекочитаемый текст ошибки fetch для модели и скринридера.
 * Сырые тексты исключений (Ktor `ResponseException` с телом ответа, `net::`-коды
 * WebView, трейсы) сюда не попадают: только короткий русский диагноз без URL
 * и внутренностей (URL уже есть в заголовке ответа тула).
 */
fun mapFetchError(e: Throwable): String {
    if (e is ResponseException) return httpStatusMessage(e.response.status.value)
    if (e is java.net.UnknownHostException) {
        return "не удаётся разрешить имя сайта — проверьте адрес и подключение"
    }
    if (e is java.net.SocketTimeoutException ||
        e is java.util.concurrent.TimeoutException ||
        e is kotlinx.coroutines.TimeoutCancellationException
    ) {
        return "превышено время ожидания ответа"
    }
    if (e is java.net.ConnectException) return "не удалось соединиться с сайтом"
    if (e is javax.net.ssl.SSLException) return "ошибка защищённого соединения (TLS)"
    // Свои диагнозы (блок локальной сети, плохой URL, таймаут рендера) — уже чистые.
    if (e is SecurityException || e is IllegalArgumentException) {
        return e.message?.take(MAX_ERROR_CHARS) ?: "ошибка запроса"
    }
    val raw = e.message?.replace(Regex("\\s+"), " ")?.take(MAX_ERROR_CHARS)
    return if (raw.isNullOrBlank()) "ошибка сети" else "ошибка сети: $raw"
}

/** Короткий русский диагноз по HTTP-статусу (без тела ответа и URL). */
fun httpStatusMessage(code: Int): String = when (code) {
    400 -> "неверный запрос (HTTP 400)"
    401, 407 -> "требуется авторизация (HTTP $code)"
    403 -> "доступ запрещён (HTTP 403)"
    404 -> "страница не найдена (HTTP 404)"
    408, 504 -> "сервер не ответил вовремя (HTTP $code)"
    410 -> "страница удалена (HTTP 410)"
    429 -> "слишком много запросов, попробуйте позже (HTTP 429)"
    in 500..599 -> "ошибка сервера (HTTP $code)"
    else -> "ошибка HTTP $code"
}

/**
 * Диагноз ошибки загрузки WebView (`WebResourceError.description`
 * вида `net::ERR_NAME_NOT_RESOLVED`) — в короткий русский текст.
 */
fun mapRenderError(description: String): String {
    val code = description.removePrefix("net::").trim()
    return when (code) {
        "ERR_NAME_NOT_RESOLVED" -> "не удаётся разрешить имя сайта — проверьте адрес и подключение"
        "ERR_INTERNET_DISCONNECTED" -> "нет подключения к интернету"
        "ERR_CONNECTION_TIMED_OUT" -> "превышено время ожидания ответа"
        "ERR_CONNECTION_REFUSED", "ERR_CONNECTION_CLOSED", "ERR_CONNECTION_RESET" ->
            "не удалось соединиться с сайтом"
        "ERR_SSL_PROTOCOL_ERROR", "ERR_CERT_AUTHORITY_INVALID", "ERR_CERT_DATE_INVALID",
        "ERR_CERT_COMMON_NAME_INVALID" -> "ошибка защищённого соединения (TLS)"
        "ERR_ACCESS_DENIED" -> "доступ запрещён"
        "ERR_FILE_NOT_FOUND" -> "страница не найдена"
        "ERR_TOO_MANY_REDIRECTS", "ERR_REDIRECT_LOOP" -> "слишком много перенаправлений"
        else -> "ошибка загрузки страницы"
    }
}

private const val MAX_ERROR_CHARS = 200
