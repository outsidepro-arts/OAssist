package ru.outsideproarts.oassist.core.provider

import android.content.Context
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSEClientException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

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

    /** Локализованное сообщение для Toast/диалога. */
    fun userMessage(context: Context): String = when (this) {
        is Http -> when (status) {
            401, 403 -> context.getString(R.string.provider_error_auth)
            404 -> context.getString(R.string.provider_error_not_found)
            else -> context.getString(R.string.provider_error_http, status)
        }
        is Network -> context.getString(R.string.provider_error_network)
        is Protocol -> context.getString(R.string.provider_error_protocol)
        is NotSupported -> context.getString(R.string.provider_error_not_supported)
        is NotConfigured -> when (reason) {
            NotConfigured.Reason.PROVIDER_MISSING -> context.getString(R.string.provider_error_no_provider)
            NotConfigured.Reason.MODEL_MISSING -> context.getString(R.string.provider_error_no_model)
        }
        is InvalidInput -> when (reason) {
            InvalidInput.Reason.NAME_REQUIRED -> context.getString(R.string.provider_error_name_required)
            InvalidInput.Reason.BASE_URL_REQUIRED -> context.getString(R.string.provider_error_base_url_required)
            InvalidInput.Reason.BASE_URL_INVALID -> context.getString(R.string.provider_error_base_url_invalid)
        }
    }

    companion object {
        private const val RAW_LIMIT = 200

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
                            return Http(status, cur.message?.take(RAW_LIMIT))
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
