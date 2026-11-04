package ru.outsideproarts.oassist.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import ru.outsideproarts.oassist.core.model.AnthropicThinkingCapabilities
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.EndpointConfig
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson

/** Native-only controls must not be inferred from the model ID or a proxy's protocol. */
internal fun EndpointConfig.isNativeAnthropic(): Boolean = runCatching {
    val url = Url(baseUrl)
    url.protocol.name == "https" && url.host == "api.anthropic.com" && url.port == 443 &&
        url.encodedPath.trimEnd('/') in setOf("", "/v1")
}.getOrDefault(false)

internal class AnthropicThinkingPolicy(
    private val endpoint: EndpointConfig,
    private val http: HttpClient,
    private val modelInfo: suspend (String) -> ModelInfo?,
) {
    private val retrieved = java.util.concurrent.ConcurrentHashMap<String, AnthropicThinkingCapabilities>()

    suspend fun configuration(modelId: String, requested: Boolean): AnthropicThinkingDto? {
        // Extra body is a shallow override. Its thinking object belongs entirely to the user.
        if (!requested || endpoint.extraBody?.containsKey("thinking") == true) return null
        val native = endpoint.isNativeAnthropic()
        val declared = modelInfo(modelId)?.anthropicThinking
        val known = declared?.takeIf { it.hasKnownMode() } ?: retrieved[modelId]?.takeIf { it.hasKnownMode() }
        val capabilities = known ?: if (native) retrieve(modelId) else declared
        return when {
            capabilities?.adaptive == true -> AnthropicThinkingDto("adaptive",
                blockBinding = if (native) AnthropicBlockBindingDto("drop_block") else null)
            capabilities?.enabled == true -> AnthropicThinkingDto("enabled", budgetTokens = 4096)
            capabilities?.hasKnownMode() == true -> throw ProviderError.InvalidInput(
                ProviderError.InvalidInput.Reason.THINKING_NOT_SUPPORTED)
            capabilities?.enabled == false -> throw ProviderError.InvalidInput(
                ProviderError.InvalidInput.Reason.THINKING_CAPABILITIES_UNKNOWN)
            !native -> AnthropicThinkingDto("enabled", budgetTokens = 4096)
            else -> throw ProviderError.InvalidInput(ProviderError.InvalidInput.Reason.THINKING_CAPABILITIES_UNKNOWN)
        }
    }

    private suspend fun retrieve(modelId: String): AnthropicThinkingCapabilities? {
        val response = http.request {
            method = HttpMethod.Get
            url.takeFrom(AnthropicClient.messagesUrl(endpoint.baseUrl).removeSuffix("messages") + "models")
            url.appendPathSegments(modelId, encodeSlash = true)
            endpoint.applyAuthHeaders(this)
            if (!headers.contains(AnthropicClient.VERSION_HEADER)) {
                headers.append(AnthropicClient.VERSION_HEADER, AnthropicClient.ANTHROPIC_VERSION)
            }
            endpoint.decorateHeaders(this, endpoint.baseUrl)
        }
        if (response.status.value !in 200..299) throw ProviderError.Http(response.status.value, null)
        val model = ProviderJson.decodeFromString(AnthropicModelDto.serializer(), response.bodyAsText())
        return model.thinkingCapabilities()?.also { retrieved[modelId] = it }
    }
}

private fun AnthropicThinkingCapabilities.hasKnownMode(): Boolean =
    adaptive == true || enabled == true || (adaptive == false && enabled == false)
