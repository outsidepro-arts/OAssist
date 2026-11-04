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
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicModelsResponseDto
import ru.outsideproarts.oassist.core.provider.anthropic.thinkingCapabilities
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
        val result = linkedMapOf<String, ModelInfo>()
        val seenCursors = mutableSetOf<CatalogCursor>()
        var cursor: CatalogCursor? = null
        var format: String? = null
        var pages = 0
        do {
            if (++pages > MAX_CATALOG_PAGES) throw ProviderError.Protocol("model catalog: page limit exceeded")
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
                // Дефолты Anthropic (20) и Gemini (50) малы для пикеров моделей.
                // Gemini отклоняет неизвестный параметр limit с HTTP 400.
                if (endpoint.protocol == ModelProtocol.GEMINI_GENERATE) {
                    parameter("pageSize", MODELS_PAGE_SIZE)
                } else if (endpoint.protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
                    parameter("limit", MODELS_LIMIT)
                }
                cursor?.let { parameter(it.parameter, it.value) }
                endpoint.decorateHeaders(this, endpoint.baseUrl)
            }
            val body = response.bodyAsText()
            endpoint.diagnostics.emit(DiagnosticKind.PROVIDER_CATALOG, operation,
                attributes = mapOf("status" to response.status.value.toString()), content = body, secrets = secrets)
            if (response.status.value !in 200..299) throw ProviderError.Http(response.status.value, body.take(64 * 1024))
            val page = parseModelCatalog(body)
            if (format != null && format != page.format) throw ProviderError.Protocol("model catalog: format changed between pages")
            format = page.format
            page.models.forEach { model ->
                val previous = result.putIfAbsent(model.id, model)
                if (previous != null && previous != model) throw ProviderError.Protocol("model catalog: conflicting duplicate model")
            }
            if (result.size > MAX_CATALOG_MODELS) throw ProviderError.Protocol("model catalog: model limit exceeded")
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) throw ProviderError.Protocol("model catalog: repeated cursor")
        } while (cursor != null)
        endpoint.diagnostics.emit(DiagnosticKind.OPERATION_END, operation, attributes = mapOf("count" to result.size.toString()))
        return result.values.toList()
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

private data class CatalogCursor(val parameter: String, val value: String)
private data class CatalogPage(val format: String, val models: List<ModelInfo>, val next: CatalogCursor?)

private fun parseModelCatalog(raw: String): CatalogPage {
    val root = runCatching { ProviderJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: throw ProviderError.Protocol("model catalog: not a JSON object")
    return when {
        "data" in root -> {
            val dto = ProviderJson.decodeFromString(AnthropicModelsResponseDto.serializer(), raw)
            val next = if (dto.hasMore) {
                val id = dto.lastId?.takeIf { it.isNotBlank() }
                    ?: throw ProviderError.Protocol("model catalog: missing next cursor")
                CatalogCursor("after_id", id)
            } else null
            CatalogPage("data", dto.data.map {
                if (it.id.isBlank()) throw ProviderError.Protocol("model catalog: empty model id")
                ModelInfo(id = it.id, label = it.displayName ?: it.name, purpose = it.type,
                    anthropicThinking = it.thinkingCapabilities())
            }, next)
        }
        "models" in root -> {
            val dto = ProviderJson.decodeFromString(GeminiModelsResponseDto.serializer(), raw)
            CatalogPage("models", dto.models.map { model ->
                    val id = model.name.removePrefix(GEMINI_MODELS_PREFIX).takeIf { it.isNotBlank() }
                        ?: throw ProviderError.Protocol("model catalog: empty model id")
                    ModelInfo(id = id, label = model.displayName, supportedGenerationMethods = model.supportedGenerationMethods)
                }, dto.nextPageToken?.takeIf { it.isNotEmpty() }?.let { CatalogCursor("pageToken", it) })
        }
        else -> throw ProviderError.Protocol("model catalog: unrecognized format")
    }
}

private const val GEMINI_MODELS_PREFIX = "models/"
private const val MODELS_PAGE_SIZE = 100
private const val MODELS_LIMIT = 1000
private const val MAX_CATALOG_PAGES = 100
private const val MAX_CATALOG_MODELS = 100_000
