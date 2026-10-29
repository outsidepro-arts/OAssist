package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import kotlinx.serialization.json.JsonObject
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicModelsResponseDto
import ru.outsideproarts.oassist.core.provider.gemini.GeminiModelsResponseDto

/**
 * Фетч списка моделей — единственная точка листинга каталога. Путь един для
 * всех API — `{base}/models`: версия (`/v1`, `/v1beta`) входит в baseUrl, как в
 * документации провайдера. Формат ответа распознаётся lenient-парсером:
 * `{"data":[{"id"}]}` (OpenAI/Anthropic, у Anthropic есть `display_name`) либо
 * `{"models":[{"name":"models/x"}]}` (Gemini). Неизвестный формат — явная
 * ошибка, а не пустой список.
 */

/**
 * URL каталога моделей: `{base}/[catalogPath]` как есть (путь — настройка
 * профиля, стандартный `models`; версия входит в baseUrl).
 */
internal fun modelsCatalogUrl(baseUrl: String, catalogPath: String? = null): String {
    val path = catalogPath?.trim('/')?.takeIf { it.isNotBlank() } ?: "models"
    return "${baseUrl.trimEnd('/')}/$path"
}

internal suspend fun fetchModelCatalog(endpoint: EndpointConfig, http: HttpClient): List<ModelInfo> {
    val response = http.request {
        method = HttpMethod.Get
        url.takeFrom(modelsCatalogUrl(endpoint.baseUrl, endpoint.catalogPath))
        endpoint.applyAuthHeaders(this)
        if (!endpoint.auth.extraHeaders.containsKey(AnthropicClient.VERSION_HEADER)) {
            // Каталог Anthropic-поверхности требует версию API на каждом запросе;
            // для остальных провайдеров заголовок безвреден.
            headers.append(AnthropicClient.VERSION_HEADER, AnthropicClient.ANTHROPIC_VERSION)
        }
        // Параметры пагинации обоих форматов (лишние игнорируются): дефолты
        // Anthropic (20) и Gemini (50) слишком малы для пикеров моделей.
        parameter("pageSize", MODELS_PAGE_SIZE)
        parameter("limit", MODELS_LIMIT)
        endpoint.decorateHeaders(this, endpoint.baseUrl)
    }
    return parseModelCatalog(response.bodyAsText())
}

private fun parseModelCatalog(raw: String): List<ModelInfo> {
    val root = runCatching { ProviderJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: throw ProviderError.Protocol("model catalog: not a JSON object")
    return when {
        "data" in root ->
            ProviderJson.decodeFromString(AnthropicModelsResponseDto.serializer(), raw)
                .data.map { ModelInfo(id = it.id, label = it.displayName) }
        "models" in root ->
            ProviderJson.decodeFromString(GeminiModelsResponseDto.serializer(), raw)
                .models.mapNotNull { model ->
                    val id = model.name.removePrefix(GEMINI_MODELS_PREFIX).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ModelInfo(id = id, label = model.displayName)
                }
        else -> throw ProviderError.Protocol("model catalog: unrecognized format")
    }
}

private const val GEMINI_MODELS_PREFIX = "models/"
private const val MODELS_PAGE_SIZE = 100
private const val MODELS_LIMIT = 1000
