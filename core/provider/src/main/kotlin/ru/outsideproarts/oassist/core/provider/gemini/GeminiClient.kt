package ru.outsideproarts.oassist.core.provider.gemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.mergeUsage
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.detectStreamErrorMessage
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Нативный клиент Gemini API (`v1beta`).
 * Auth — `x-goog-api-key` (не Bearer); `baseUrl` — хост
 * (`https://generativelanguage.googleapis.com`) или с суффиксом `/v1beta`.
 */
class GeminiClient private constructor(
    private val profile: ProviderProfile,
    private val auth: ProviderAuth,
    private val http: HttpClient,
) : ModelClient {

    override suspend fun fetchModels(): List<ModelInfo> {
        val response = http.request {
            method = HttpMethod.Get
            url.takeFrom(modelsUrl(profile.baseUrl))
            applyAuth()
            // Листинг без пагинации: дефолтной страницы хватает для пикеров.
            parameter("pageSize", MODELS_PAGE_SIZE)
        }
        val dto = ProviderJson.decodeFromString(
            GeminiModelsResponseDto.serializer(),
            response.bodyAsText(),
        )
        return dto.models.mapNotNull { model ->
            val id = model.name.removePrefix(MODELS_PREFIX).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // Capability из листинга не вывести (`supportedGenerationMethods`
            // про инструменты не говорит) — честный emptySet, проба в M3.3.
            ModelInfo(id = id, label = model.displayName)
        }
    }

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
        val requestDto = GeminiGenerateRequest(
            contents = listOf(GeminiContentDto(parts = listOf(GeminiPartDto(text = PROBE_TEXT)))),
            tools = listOf(GeminiToolsDto(functionDeclarations = listOf(
                GeminiFunctionDeclarationDto(name = "probe", description = "probe"),
            ))),
        )
        try {
            val response = http.request {
                method = HttpMethod.Post
                url.takeFrom(contentUrl(profile.baseUrl, modelId))
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            }
            ProviderJson.decodeFromString(
                GeminiStreamChunkDto.serializer(),
                response.bodyAsText(),
            )
            return setOf(ModelCapability.TOOLS)
        } catch (e: ResponseException) {
            // 400 — модель не приняла `tools`: сигнал, а не ошибка.
            if (e.response.status == HttpStatusCode.BadRequest) return emptySet()
            throw e
        }
    }

    override fun chatCompletionStream(
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchemaDto>,
    ): Flow<ChatChunk> = flow {
        val (system, contents) = mapGeminiContents(messages)
        val requestDto = GeminiGenerateRequest(
            systemInstruction = system,
            contents = contents,
            tools = mapGeminiTools(tools),
        )
        http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(generateUrl(profile.baseUrl, modelId))
                applyAuth()
                contentType(ContentType.Application.Json)
                parameter("alt", "sse")
                setBody(requestDto)
            },
        ) {
            var nextId = 0
            var finish: String? = null
            var usage: ProviderUsage? = null
            incoming.collect { event ->
                val data = event.data ?: return@collect
                val dto = runCatching {
                    ProviderJson.decodeFromString(GeminiStreamChunkDto.serializer(), data)
                }.getOrNull()
                if (dto == null) {
                    // Ошибка API приходит тем же потоком другим JSON-объектом.
                    detectStreamErrorMessage(data)
                        ?.let { throw ProviderError.Protocol("gemini: $it") }
                    return@collect
                }
                // Usage-only чанк (финальный, с пустыми candidates) — читаем
                // usageMetadata до гейта, иначе статистика теряется.
                dto.usageMetadata?.toProviderUsage()?.let { usage = mergeUsage(usage, it) }
                val chunk = dto.takeIf { it.candidates.isNotEmpty() }
                if (chunk == null) {
                    // `{"error":...}` тоже декодируется как GeminiStreamChunkDto —
                    // все поля с дефолтами; детектор идёт по сырому JSON.
                    detectStreamErrorMessage(data)
                        ?.let { throw ProviderError.Protocol("gemini: $it") }
                    return@collect
                }
                val extracted = extractGeminiChunk(chunk, nextId)
                nextId = extracted.nextId
                extracted.finishReason?.let { finish = it }
                extracted.chunks.forEach { emit(it) }
            }
            // Без finish_reason соединение закрылось досрочно: ответ мог быть усечён.
            emit(ChatChunk.Finish(reason = finish ?: "STOP", abrupt = finish == null, usage = usage))
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        auth.bearerToken?.let {
            headers.append(API_KEY_HEADER, String(it))
        }
        auth.extraHeaders.forEach { (name, value) ->
            headers.append(name, value)
        }
    }

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"

        private const val MODELS_PREFIX = "models/"
        private const val MODELS_PAGE_SIZE = 100
        private const val PROBE_TEXT = "hi"

        /** Нестримовый URL генерации (для пробы): `models/{id}:generateContent`. */
        internal fun contentUrl(baseUrl: String, modelId: String): String {
            val base = baseUrl.trimEnd('/')
            val id = modelId.removePrefix(MODELS_PREFIX)
            val path = "models/$id:generateContent"
            return if (base.endsWith("/v1beta")) "$base/$path" else "$base/v1beta/$path"
        }

        internal fun modelsUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.endsWith("/v1beta")) "$base/models" else "$base/v1beta/models"
        }

        internal fun generateUrl(baseUrl: String, modelId: String): String {
            val base = baseUrl.trimEnd('/')
            val id = modelId.removePrefix(MODELS_PREFIX)
            val path = "models/$id:streamGenerateContent"
            return if (base.endsWith("/v1beta")) "$base/$path" else "$base/v1beta/$path"
        }

        suspend fun fromProfile(
            profile: ProviderProfile,
            secretStore: SecretStore,
            httpClient: HttpClient,
            overrides: ProviderAuth? = null,
        ): GeminiClient {
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
            return GeminiClient(
                profile,
                ProviderAuth(bearerToken = apiKey, extraHeaders = extras),
                httpClient,
            )
        }
    }
}

/** Usage-метаданные Gemini в каноническую модель; thinking-токены — в output. */
internal fun GeminiUsageMetadataDto.toProviderUsage(): ProviderUsage? {
    val output = sumNullable(candidatesTokenCount, thoughtsTokenCount)
    val mapped = ProviderUsage.of(
        inputTokens = promptTokenCount,
        outputTokens = output,
        totalTokens = totalTokenCount,
        cachedTokens = cachedContentTokenCount,
    )
    return mapped.takeUnless { it.isEmpty }
}

private fun sumNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}
