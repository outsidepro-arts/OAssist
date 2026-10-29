package ru.outsideproarts.oassist.core.provider

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.AuthScheme
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient
import java.util.Base64

private val SECRET_FILLER: Char = Char.MIN_VALUE

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
 * Точка подключения протокольного клиента: базовый URL (корень API вместе с
 * версией, как в документации провайдера) + резолвленные секреты + оформление
 * запроса (схема auth, заголовки агрегатора). Все пути дописываются как есть.
 */
class EndpointConfig(
    val protocol: ModelProtocol,
    val baseUrl: String,
    val auth: ProviderAuth,
    val authScheme: AuthScheme = AuthScheme.NATIVE,
    val apiKeyHeaderName: String? = null,
    val requestHeaders: RequestHeaders = RequestHeaders.NONE,
    /** Дополнительные ключи тела запросов к модели (мерж поверх сериализованного DTO). */
    val extraBody: JsonObject? = null,
    /** Путь каталога моделей; null — стандартный `models` (итог: `{base}/models`). */
    val catalogPath: String? = null,
) {
    /**
     * Заголовки авторизации по [authScheme]. [AuthScheme.NATIVE] — key-based
     * маппинг протокола: Bearer для OpenAI-протоколов, `x-api-key` для Anthropic,
     * `x-goog-api-key` для Gemini; логин/пароль нативным схемам не нужны.
     */
    fun applyAuthHeaders(builder: HttpRequestBuilder) {
        when (authScheme) {
            AuthScheme.NONE -> {}
            AuthScheme.BEARER -> auth.apiKey?.let {
                builder.headers.append(HttpHeaders.Authorization, "Bearer " + String(it))
            }
            AuthScheme.API_KEY_HEADER -> auth.apiKey?.let {
                val name = apiKeyHeaderName?.takeIf { n -> n.isNotBlank() } ?: nativeKeyHeader(protocol)
                if (name.equals(HttpHeaders.Authorization, ignoreCase = true)) {
                    builder.headers.append(HttpHeaders.Authorization, "Bearer " + String(it))
                } else {
                    builder.headers.append(name, String(it))
                }
            }
            AuthScheme.BASIC -> {
                val user = auth.basicUser ?: return
                val password = auth.basicPassword?.let { String(it) }.orEmpty()
                val encoded = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                builder.headers.append(HttpHeaders.Authorization, "Basic $encoded")
            }
            AuthScheme.NATIVE -> {
                val key = auth.apiKey ?: return
                when (protocol) {
                    ModelProtocol.OPENAI_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES ->
                        builder.headers.append(HttpHeaders.Authorization, "Bearer " + String(key))
                    ModelProtocol.ANTHROPIC_MESSAGES ->
                        builder.headers.append(AnthropicClient.API_KEY_HEADER, String(key))
                    ModelProtocol.GEMINI_GENERATE ->
                        builder.headers.append(GeminiClient.API_KEY_HEADER, String(key))
                }
            }
        }
    }

    /** Заголовки агрегатора + [ProviderAuth.extraHeaders] поверх протокольного auth. */
    fun decorateHeaders(builder: HttpRequestBuilder, dialogKey: String) {
        requestHeaders.headersFor(dialogKey).forEach { (name, value) ->
            builder.headers.append(name, value)
        }
        auth.extraHeaders.forEach { (name, value) ->
            builder.headers.append(name, value)
        }
    }

    companion object {
        /** Имя key-заголовка протокола для [AuthScheme.API_KEY_HEADER] без явного имени. */
        fun nativeKeyHeader(protocol: ModelProtocol): String = when (protocol) {
            ModelProtocol.OPENAI_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES -> HttpHeaders.Authorization
            ModelProtocol.ANTHROPIC_MESSAGES -> AnthropicClient.API_KEY_HEADER
            ModelProtocol.GEMINI_GENERATE -> GeminiClient.API_KEY_HEADER
        }
    }
}

/**
 * Разбор [ru.outsideproarts.oassist.core.model.ProviderProfile.extraBody] в
 * JSON-объект для мержа в тела запросов; null — не задано или некорректно
 * (некорректность отсекается валидацией при сохранении).
 */
internal fun extraBodyJson(extraBody: String?): JsonObject? {
    if (extraBody.isNullOrBlank()) return null
    return runCatching { ProviderJson.parseToJsonElement(extraBody) as? JsonObject }.getOrNull()
}

/**
 * Тело запроса к модели с учётом [EndpointConfig.extraBody]: ключи дополнений
 * мержатся поверх сериализованного DTO (дубль ключа — дополнение побеждает).
 * Сериализация — тем же wire-JSON, что и ContentNegotiation: база тела запроса
 * с дополнениями обязана совпадать с обычным телом.
 */
internal inline fun <reified T> HttpRequestBuilder.setBodyDecorated(endpoint: EndpointConfig, body: T) {
    val extra = endpoint.extraBody
    if (extra == null) {
        setBody(body)
        return
    }
    setBody(JsonObject(ru.outsideproarts.oassist.core.net.WireJson.encodeToJsonElement(body).jsonObject + extra))
}

/** Разделитель «головы» диалога: прежний символ-разделитель без эскейпов. */
private val DIALOG_KEY_SEPARATOR: String = Char.MIN_VALUE.toString()

/** «Голова» диалога: первое system-сообщение + первый user-вопрос. */
internal fun dialogKey(messages: List<ChatMessage>): String {
    val system = messages.firstOrNull { it.role == ChatRole.SYSTEM }?.content.orEmpty()
    val firstUser = messages.firstOrNull { it.role == ChatRole.USER }?.content.orEmpty()
    return system + DIALOG_KEY_SEPARATOR + firstUser
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
    val apiKey = overrides?.apiKey ?: profile.apiKeyRef?.let { secretStore.get(it) }
    val basicUser = overrides?.basicUser ?: profile.basicUserRef?.let { ref ->
        secretStore.get(ref)?.let { value ->
            try {
                String(value)
            } finally {
                value.fill(SECRET_FILLER)
            }
        }
    }
    val basicPassword = overrides?.basicPassword ?: profile.basicPasswordRef?.let { secretStore.get(it) }
    val extras = if (overrides != null && overrides.extraHeaders.isNotEmpty()) {
        overrides.extraHeaders
    } else {
        profile.extraHeaders.mapNotNull { (name, alias) ->
            val value = secretStore.get(alias) ?: return@mapNotNull null
            try {
                name to String(value)
            } finally {
                value.fill(SECRET_FILLER)
            }
        }.toMap()
    }
    return ProviderAuth(
        apiKey = apiKey,
        basicUser = basicUser,
        basicPassword = basicPassword,
        extraHeaders = extras,
    )
}
