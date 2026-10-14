package ru.outsideproarts.oassist.core.provider

import android.content.Context
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSEClientException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/** Категория ошибки провайдера для пользовательского сообщения. */
internal enum class ProviderErrorKind {
    /** Бесплатный тир (только внутри клиента OpenCode). */
    FREE_TIER,
    /** Модель заблокирована в регионе клиента. */
    REGION_BLOCKED,
    /** Модель снята с обслуживания (есть замена). */
    MODEL_DEPRECATED,
    /** Модель не обслуживается выбранным проводным протоколом (сигнал к фолбэку). */
    MODEL_PROTOCOL_UNSUPPORTED,
    /** Доступ к модели отключён для этого ключа/аккаунта. */
    MODEL_ACCESS,
    /** Апстрим модели недоступен (временный сбой на стороне провайдера). */
    UPSTREAM_UNAVAILABLE,
    AUTH,
    NOT_FOUND,
    HTTP,
    NETWORK,
    PROTOCOL,
    NOT_SUPPORTED,
    NOT_CONFIGURED,
    INVALID_INPUT,
}

/**
 * Классификация ошибки по телу/сообщению ответа (без учёта статуса):
 * null — маркеров нет. Маркеры — реальные тела ошибок OpenCode Zen/Go
 * (FreeTierError, ModelProtocolUnsupported, ModelDeprecated и т.д.).
 */
internal fun classifyErrorText(text: String?): ProviderErrorKind? {
    if (text.isNullOrBlank()) return null
    val t = text.lowercase()
    return when {
        "freetiererror" in t || "free tier can only be used" in t -> ProviderErrorKind.FREE_TIER
        "unsupported_country_region_territory" in t ||
            "country, region, or territory not supported" in t -> ProviderErrorKind.REGION_BLOCKED
        "modeldeprecated" in t || "has been deprecated" in t || "is deprecated" in t ->
            ProviderErrorKind.MODEL_DEPRECATED
        "modelprotocolunsupported" in t || "does not support this protocol" in t ->
            ProviderErrorKind.MODEL_PROTOCOL_UNSUPPORTED
        "model access is disabled" in t -> ProviderErrorKind.MODEL_ACCESS
        "upstream request failed" in t || "endpoint is unavailable" in t ->
            ProviderErrorKind.UPSTREAM_UNAVAILABLE
        else -> null
    }
}

/** Классификация HTTP-ошибки: маркеры тела, затем статус. */
internal fun classifyProviderError(status: Int, text: String?): ProviderErrorKind {
    classifyErrorText(text)?.let { return it }
    return when (status) {
        401, 403 -> ProviderErrorKind.AUTH
        404 -> ProviderErrorKind.NOT_FOUND
        else -> ProviderErrorKind.HTTP
    }
}

/** Имя замены из `ModelDeprecated`-ответа (`metadata.replacement` или «Use X instead»). */
internal fun extractReplacement(text: String?): String? {
    if (text == null) return null
    Regex(""""replacement"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let { return it }
    return Regex("""[Uu]se ([A-Za-z0-9._-]+) instead""").find(text)?.groupValues?.get(1)
}

/** Явный сигнал Zen/Go «не тот проводной протокол» — гейт авто-фолбэка в агрегаторах. */
internal fun isProtocolUnsupportedMessage(text: String?): Boolean =
    classifyErrorText(text) == ProviderErrorKind.MODEL_PROTOCOL_UNSUPPORTED

/** [isProtocolUnsupportedMessage] для исключения (с разбором cause-цепочки). */
internal fun isProtocolUnsupported(t: Throwable): Boolean {
    val err = runCatching { ProviderError.from(t) }.getOrNull() ?: return false
    return err.kind() == ProviderErrorKind.MODEL_PROTOCOL_UNSUPPORTED
}

/**
 * Ошибка провайдера — обёртка, чтобы слои выше не ловили голое исключение.
 * Пользовательский текст — только через [userMessage] (ресурсы, ru/en); тела ответов
 * и секреты в него не попадают.
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    open val status: Int = -1
    open val raw: String? = null

    class Http(val statusCode: Int, body: String?) : ProviderError("HTTP $statusCode: ${body ?: "<empty>"}") {
        override val status: Int = statusCode
        override val raw: String? = body
    }

    class Network(message: String, cause: Throwable? = null) : ProviderError(message, cause)

    class Protocol(reason: String) : ProviderError("Protocol error: $reason")

    /** Тип провайдера ещё не реализован (GEMINI до M3.2). */
    class NotSupported(val kind: String) : ProviderError("Провайдер $kind ещё не реализован")

    /** Отправка невозможна: провайдер или модель не настроены. */
    class NotConfigured(val reason: Reason) : ProviderError("Not configured: $reason") {
        enum class Reason { PROVIDER_MISSING, MODEL_MISSING }
    }

    /** Локальная ошибка валидации полей профиля. */
    class InvalidInput(val reason: Reason) : ProviderError("Invalid input: $reason") {
        enum class Reason { NAME_REQUIRED, BASE_URL_REQUIRED, BASE_URL_INVALID }
    }

    /** Категория ошибки — по телу ответа (типизированные ошибки Zen/Go) и статусу. */
    internal fun kind(): ProviderErrorKind = when (this) {
        is Http -> classifyProviderError(status, raw)
        is Network -> ProviderErrorKind.NETWORK
        is Protocol -> classifyErrorText(message) ?: ProviderErrorKind.PROTOCOL
        is NotSupported -> ProviderErrorKind.NOT_SUPPORTED
        is NotConfigured -> ProviderErrorKind.NOT_CONFIGURED
        is InvalidInput -> ProviderErrorKind.INVALID_INPUT
    }

    /** Локализованное сообщение для Toast/диалога. */
    fun userMessage(context: Context): String = when (kind()) {
        ProviderErrorKind.FREE_TIER -> context.getString(R.string.provider_error_free_tier)
        ProviderErrorKind.REGION_BLOCKED -> context.getString(R.string.provider_error_region_blocked)
        ProviderErrorKind.MODEL_DEPRECATED -> {
            val replacement = extractReplacement(raw ?: message)
            if (replacement != null) {
                context.getString(R.string.provider_error_model_deprecated, replacement)
            } else {
                context.getString(R.string.provider_error_model_deprecated_plain)
            }
        }
        ProviderErrorKind.MODEL_PROTOCOL_UNSUPPORTED -> context.getString(R.string.provider_error_model_protocol)
        ProviderErrorKind.MODEL_ACCESS -> context.getString(R.string.provider_error_model_access)
        ProviderErrorKind.UPSTREAM_UNAVAILABLE -> context.getString(R.string.provider_error_upstream_unavailable)
        ProviderErrorKind.AUTH -> context.getString(R.string.provider_error_auth)
        ProviderErrorKind.NOT_FOUND -> context.getString(R.string.provider_error_not_found)
        ProviderErrorKind.HTTP -> context.getString(R.string.provider_error_http, status)
        ProviderErrorKind.NETWORK -> context.getString(R.string.provider_error_network)
        ProviderErrorKind.PROTOCOL -> context.getString(R.string.provider_error_protocol)
        ProviderErrorKind.NOT_SUPPORTED -> context.getString(R.string.provider_error_not_supported)
        ProviderErrorKind.NOT_CONFIGURED -> when ((this as NotConfigured).reason) {
            NotConfigured.Reason.PROVIDER_MISSING -> context.getString(R.string.provider_error_no_provider)
            NotConfigured.Reason.MODEL_MISSING -> context.getString(R.string.provider_error_no_model)
        }
        ProviderErrorKind.INVALID_INPUT -> when ((this as InvalidInput).reason) {
            InvalidInput.Reason.NAME_REQUIRED -> context.getString(R.string.provider_error_name_required)
            InvalidInput.Reason.BASE_URL_REQUIRED -> context.getString(R.string.provider_error_base_url_required)
            InvalidInput.Reason.BASE_URL_INVALID -> context.getString(R.string.provider_error_base_url_invalid)
        }
    }

    companion object {
        private const val RAW_LIMIT = 400

        /**
         * Маппинг любого исключения запроса в [ProviderError].
         * Ktor заворачивает ошибки SSE-стрима в `SSEClientException`
         * (IllegalStateException), поэтому идём по cause-цепочке: иначе
         * HTTP-статус (401/429/503) терялся бы в общей «сетевой ошибке».
         */
        fun from(t: Throwable): ProviderError {
            var cur: Throwable? = t
            while (cur != null) {
                when (cur) {
                    is ProviderError -> return cur
                    // Отмена может быть завёрнута (напр. в SSEClientException):
                    // бросаем именно её, иначе корутина «завершится ошибкой» вместо отмены.
                    is CancellationException -> throw cur
                    // Обёртка SSE-стрима: статус несёт сама (без cause при обрыве
                    // handshake), иначе — идём глубже по цепочке.
                    is SSEClientException -> {
                        val status = runCatching { cur.response?.status?.value }.getOrNull()
                        if (status != null && status !in 200..299) {
                            // Тело ошибки (типизированные ошибки Zen/Go) лежит в
                            // ResponseException по цепочке — без него классификация слепа.
                            var cause: Throwable? = cur.cause
                            var body: String? = null
                            while (cause != null) {
                                if (cause is ResponseException) {
                                    body = cause.message
                                    break
                                }
                                cause = cause.cause
                            }
                            return Http(status, (body ?: cur.message)?.take(RAW_LIMIT))
                        }
                        cur = cur.cause
                        continue
                    }
                    is ResponseException -> return Http(cur.response.status.value, cur.message?.take(RAW_LIMIT))
                    is SerializationException -> return Protocol(cur.message ?: "parse error")
                }
                cur = cur.cause
            }
            return Network(t.message ?: t::class.java.simpleName, t)
        }
    }
}
