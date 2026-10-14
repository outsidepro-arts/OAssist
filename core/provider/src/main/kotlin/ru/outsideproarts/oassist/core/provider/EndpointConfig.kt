package ru.outsideproarts.oassist.core.provider

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile

/**
 * Как строить пути API относительно [EndpointConfig.baseUrl].
 */
enum class UrlStyle {
    /** Канонические пути протокола: Gemini добавляет `/v1beta`, Anthropic — `/v1`. */
    NATIVE,

    /**
     * Пути заданы самим baseUrl (агрегаторы вроде OpenCode Zen/Go): сегменты
     * дописываются как есть — `{base}/chat/completions`, `{base}/responses`,
     * `{base}/messages`, `{base}/models/{id}:streamGenerateContent`.
     * Контракт: baseUrl уже включает `/v1` (`https://opencode.ai/zen/v1`).
     */
    FLAT,
}

/**
 * Дополнительные заголовки запроса поверх протокольного auth — слой агрегатора.
 */
fun interface RequestHeaders {
    /**
     * [dialogKey] — стабильный ключ диалога («голова»: system + первый user)
     * или ключ операции (для списков — baseUrl).
     */
    fun headersFor(dialogKey: String): Map<String, String>

    companion object {
        val NONE = RequestHeaders { emptyMap() }
    }
}

/**
 * Точка подключения протокольного клиента: базовый URL + резолвленные секреты
 * + оформление запроса (пути, заголовки агрегатора).
 */
class EndpointConfig(
    val protocol: ModelProtocol,
    val baseUrl: String,
    val auth: ProviderAuth,
    val urlStyle: UrlStyle = UrlStyle.NATIVE,
    val requestHeaders: RequestHeaders = RequestHeaders.NONE,
) {
    /** Заголовки агрегатора + [ProviderAuth.extraHeaders] поверх протокольного auth. */
    fun decorateHeaders(builder: HttpRequestBuilder, dialogKey: String) {
        requestHeaders.headersFor(dialogKey).forEach { (name, value) ->
            builder.headers.append(name, value)
        }
        auth.extraHeaders.forEach { (name, value) ->
            builder.headers.append(name, value)
        }
    }
}

/** «Голова» диалога: первое system-сообщение + первый user-вопрос. */
internal fun dialogKey(messages: List<ChatMessage>): String {
    val system = messages.firstOrNull { it.role == ChatRole.SYSTEM }?.content.orEmpty()
    val firstUser = messages.firstOrNull { it.role == ChatRole.USER }?.content.orEmpty()
    return "$system\u0000$firstUser"
}

/**
 * Резолв секретов профиля: plaintext только в [ProviderAuth] (CharArray),
 * alias'ы SecretStore никогда не переезжают в модель. [overrides] —
 * несохранённые значения формы (проверка провайдера до сохранения).
 */
internal suspend fun resolveProviderAuth(
    profile: ProviderProfile,
    secretStore: SecretStore,
    overrides: ProviderAuth?,
): ProviderAuth {
    val apiKey = overrides?.bearerToken ?: profile.apiKeyRef?.let { secretStore.get(it) }
    val extras = if (overrides != null && overrides.extraHeaders.isNotEmpty()) {
        overrides.extraHeaders
    } else {
        profile.extraHeaders.mapNotNull { (name, alias) ->
            val value = secretStore.get(alias) ?: return@mapNotNull null
            try {
                name to String(value)
            } finally {
                value.fill('\u0000')
            }
        }.toMap()
    }
    return ProviderAuth(bearerToken = apiKey, extraHeaders = extras)
}
