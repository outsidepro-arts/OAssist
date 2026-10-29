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
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.model.diagnostics.emit
import kotlinx.coroutines.CancellationException
import io.ktor.client.plugins.ResponseException

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
    val operation = Diagnostics.newOperationId()
    val parent = kotlinx.coroutines.currentCoroutineContext()[ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext]?.operationId
    val secrets = endpoint.diagnosticSecrets()
    val safeUrl = runCatching {
        val uri = java.net.URI(modelsCatalogUrl(endpoint.baseUrl, endpoint.catalogPath))
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrDefault("[invalid URL]")
    endpoint.diagnostics.emit(DiagnosticKind.OPERATION_START, operation,
        parentOperationId = parent,
        attributes = mapOf("operation" to "catalog", "protocol" to endpoint.protocol.name),
        content = safeUrl, secrets = secrets)
    try {
    val response = http.request {
        attributes.put(ru.outsideproarts.oassist.core.net.DiagnosticOperationKey, operation)
        attributes.put(ru.outsideproarts.oassist.core.net.DiagnosticAttemptKey, java.util.concurrent.atomic.AtomicInteger())
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
    val body = response.bodyAsText()
    endpoint.diagnostics.emit(DiagnosticKind.PROVIDER_CATALOG, operation,
        attributes = mapOf("status" to response.status.value.toString()), content = body, secrets = secrets)
    val result = parseModelCatalog(body)
    endpoint.diagnostics.emit(DiagnosticKind.OPERATION_END, operation, attributes = mapOf("count" to result.size.toString()))
    return result
    } catch (e: CancellationException) {
        endpoint.diagnostics.emit(DiagnosticKind.OPERATION_CANCELLED, operation)
        throw e
    } catch (e: Throwable) {
        val status = (e as? ResponseException)?.response?.status?.value?.toString().orEmpty()
        val errorBody = (e as? ResponseException)?.let { response ->
            try {
                response.response.bodyAsText().take(64 * 1024)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        endpoint.diagnostics.emit(DiagnosticKind.OPERATION_ERROR, operation,
            attributes = mapOf("error" to e.javaClass.simpleName, "status" to status),
            content = errorBody, secrets = secrets)
        throw e
    }
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
