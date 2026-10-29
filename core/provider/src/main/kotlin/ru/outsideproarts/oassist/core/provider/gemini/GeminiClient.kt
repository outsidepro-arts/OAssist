package ru.outsideproarts.oassist.core.provider.gemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import ru.outsideproarts.oassist.core.provider.setBodyDecorated
import ru.outsideproarts.oassist.core.provider.diagnosticSecrets
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
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.provider.EndpointConfig
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.RateLimitHeaders
import ru.outsideproarts.oassist.core.provider.detectStreamErrorMessage
import ru.outsideproarts.oassist.core.provider.dialogKey
import ru.outsideproarts.oassist.core.provider.fetchModelCatalog
import ru.outsideproarts.oassist.core.provider.isProtocolUnsupportedMessage
import ru.outsideproarts.oassist.core.provider.mapProviderUsage
import ru.outsideproarts.oassist.core.provider.resolveProviderAuth
import ru.outsideproarts.oassist.core.provider.withRateLimits
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Нативный клиент Gemini API (`v1beta`).
 * Auth — `x-goog-api-key` (не Bearer); `baseUrl` — хост
 * (`https://generativelanguage.googleapis.com`) или с суффиксом `/v1beta`.
 */
class GeminiClient internal constructor(
    private val endpoint: EndpointConfig,
    private val http: HttpClient,
) : ModelClient {
    override fun diagnosticSecrets(): Collection<String> = endpoint.diagnosticSecrets()

    override suspend fun fetchModels(): List<ModelInfo> = fetchModelCatalog(endpoint, http)

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
        val diagnosticParent = kotlinx.coroutines.currentCoroutineContext()[ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext]
        val requestDto = GeminiGenerateRequest(
            contents = listOf(GeminiContentDto(parts = listOf(GeminiPartDto(text = PROBE_TEXT)))),
            tools = listOf(GeminiToolsDto(functionDeclarations = listOf(
                GeminiFunctionDeclarationDto(name = "probe", description = "probe"),
            ))),
        )
        try {
            val response = http.request {
                method = HttpMethod.Post
                url.takeFrom(contentUrl(endpoint.baseUrl, modelId))
                applyAuth(dialogKey = dialogKey(emptyList()))
                contentType(ContentType.Application.Json)
                setBodyDecorated(endpoint, requestDto, diagnosticParent)
            }
            ProviderJson.decodeFromString(
                GeminiStreamChunkDto.serializer(),
                response.bodyAsText(),
            )
            return setOf(ModelCapability.TOOLS)
        } catch (e: ResponseException) {
            // 400 — модель не приняла `tools`: сигнал, а не ошибка.
            // «Не тот протокол» (агрегаторы) пробрасывается — гейт фолбэка.
            if (e.response.status == HttpStatusCode.BadRequest && !isProtocolUnsupportedMessage(e.message)) {
                ru.outsideproarts.oassist.core.provider.recordProviderHttpError(endpoint.diagnostics, diagnosticParent?.operationId, e, endpoint.diagnosticSecrets() + diagnosticParent?.secrets.orEmpty())
                return emptySet()
            }
            throw e
        }
    }

    override fun chatCompletionStream(
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchemaDto>,
        reasoningEnabled: Boolean,
    ): Flow<ChatChunk> = flow {
        val diagnosticParent = kotlinx.coroutines.currentCoroutineContext()[ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext]
        val (system, contents) = mapGeminiContents(messages)
        val requestDto = GeminiGenerateRequest(
            systemInstruction = system,
            contents = contents,
            tools = mapGeminiTools(tools),
        )
        val capture = ru.outsideproarts.oassist.core.provider.SseDiagnosticCapture(endpoint,
            diagnosticParent)
        try { http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(generateUrl(endpoint.baseUrl, modelId))
                applyAuth(dialogKey = dialogKey(messages))
                contentType(ContentType.Application.Json)
                parameter("alt", "sse")
                setBodyDecorated(endpoint, requestDto, diagnosticParent)
            },
        ) {
            var nextId = 0
            var finish: String? = null
            var usage: ProviderUsage? = null
            // «Сколько осталось до ограничения» — из заголовков ответа.
            val rateLimits = RateLimitHeaders.parse(call.response.headers)
            incoming.collect { event ->
                val data = event.data ?: return@collect
                capture.add(data)
                val dto = runCatching {
                    ProviderJson.decodeFromString(GeminiStreamChunkDto.serializer(), data)
                }.getOrNull()
                if (dto == null) {
                    // Ошибка API приходит тем же потоком другим JSON-объектом.
                    detectStreamErrorMessage(data)
                        ?.let { throw ProviderError.Protocol("gemini: $it") }
                    return@collect
                }
                // usageMetadata кумулятивен и приходит на каждом чанке — берём
                // последний снимок целиком: суммирование задвоило бы счётчики.
                dto.usageMetadata?.toProviderUsage()?.let { usage = it }
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
            emit(ChatChunk.Finish(
                reason = finish ?: "STOP",
                abrupt = finish == null,
                usage = usage.withRateLimits(rateLimits),
            ))
        } } finally { runCatching { capture.flush() } }
    }

    private fun HttpRequestBuilder.applyAuth(dialogKey: String) {
        // Размещение ключа — по схеме авторизации профиля (нативная — x-goog-api-key).
        endpoint.applyAuthHeaders(this)
        endpoint.decorateHeaders(this, dialogKey)
    }

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"

        private const val MODELS_PREFIX = "models/"
        private const val PROBE_TEXT = "hi"

        /** Нестримовый URL генерации (для пробы): `{base}/models/{id}:generateContent`. */
        internal fun contentUrl(baseUrl: String, modelId: String): String =
            "${baseUrl.trimEnd('/')}/models/${modelId.removePrefix(MODELS_PREFIX)}:generateContent"

        /** URL стрима: `{base}/models/{id}:streamGenerateContent`. */
        internal fun generateUrl(baseUrl: String, modelId: String): String =
            "${baseUrl.trimEnd('/')}/models/${modelId.removePrefix(MODELS_PREFIX)}:streamGenerateContent"

        suspend fun fromProfile(
            diagnostics: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder,
            profile: ProviderProfile,
            secretStore: SecretStore,
            httpClient: HttpClient,
            overrides: ProviderAuth? = null,
        ): GeminiClient = GeminiClient(
            EndpointConfig(
                diagnostics = diagnostics,
                protocol = ModelProtocol.GEMINI_GENERATE,
                baseUrl = profile.baseUrl,
                auth = resolveProviderAuth(profile, secretStore, overrides),
            ),
            httpClient,
        )
    }
}

/**
 * Usage-метаданные Gemini в каноническую модель: thinking-токены — в output
 * (и отдельной строкой «мышление»), токены результатов инструментов — в
 * toolTokens (входят в total провайдера, но не в prompt/candidates).
 * Cost/opencode — расширение OpenCode на Gemini-протоколе.
 */
internal fun GeminiUsageMetadataDto.toProviderUsage(): ProviderUsage? = mapProviderUsage(
    inputTokens = promptTokenCount,
    outputTokens = sumNullable(candidatesTokenCount, thoughtsTokenCount),
    totalTokens = totalTokenCount,
    cachedTokens = cachedContentTokenCount,
    reasoningTokens = thoughtsTokenCount,
    toolTokens = toolUsePromptTokenCount,
    costUsd = cost,
    openCode = opencode,
)

private fun sumNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}
