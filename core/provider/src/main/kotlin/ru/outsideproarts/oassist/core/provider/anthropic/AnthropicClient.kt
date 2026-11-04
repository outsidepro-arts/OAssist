package ru.outsideproarts.oassist.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import ru.outsideproarts.oassist.core.provider.dialogKey
import ru.outsideproarts.oassist.core.provider.fetchModelCatalog
import ru.outsideproarts.oassist.core.provider.isProtocolUnsupportedMessage
import ru.outsideproarts.oassist.core.provider.resolveProviderAuth
import ru.outsideproarts.oassist.core.provider.withRateLimits
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.model.diagnostics.emit

/**
 * Нативный клиент Anthropic Messages API.
 * Auth — `x-api-key` + `anthropic-version` (не Bearer); `baseUrl` — хост
 * (`https://api.anthropic.com`) или с суффиксом `/v1`, удвоение не допускается.
 */
class AnthropicClient internal constructor(
    private val endpoint: EndpointConfig,
    private val http: HttpClient,
    modelInfo: suspend (String) -> ModelInfo? = { null },
) : ModelClient {
    private val thinkingPolicy = AnthropicThinkingPolicy(endpoint, http, modelInfo)
    override fun diagnosticSecrets(): Collection<String> = endpoint.diagnosticSecrets()

    override suspend fun fetchModels(): List<ModelInfo> = fetchModelCatalog(endpoint, http)

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
        val diagnosticParent = kotlinx.coroutines.currentCoroutineContext()[ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext]
        val requestDto = AnthropicMessagesRequest(
            model = modelId,
            maxTokens = PROBE_MAX_TOKENS,
            messages = listOf(AnthropicMessageDto("user", JsonPrimitive(PROBE_TEXT))),
            tools = listOf(probeTool()),
            stream = false,
        )
        try {
            val response = http.request {
                method = HttpMethod.Post
                url.takeFrom(messagesUrl(endpoint.baseUrl))
                applyAuth(dialogKey = dialogKey(emptyList()))
                contentType(ContentType.Application.Json)
                setBodyDecorated(endpoint, requestDto, diagnosticParent)
            }
            ProviderJson.decodeFromString(
                AnthropicMessageResponseDto.serializer(),
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
        val modelOverride = endpoint.extraBody?.get("model")
        val effectiveModelId = if (modelOverride == null) modelId else {
            (modelOverride as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: throw ProviderError.InvalidInput(ProviderError.InvalidInput.Reason.MODEL_OVERRIDE_INVALID)
        }
        val replayOrigin = ProviderJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
            listOf("anthropic_messages", endpoint.baseUrl.trimEnd('/'), endpoint.replayIdentity.orEmpty(), effectiveModelId))
        // A capability flag is not an instruction to discard actual native always-on thinking.
        // Only a validated same-origin snapshot can establish this continuation; never legacy signatures.
        val continuation = endpoint.isNativeAnthropic() && endpoint.extraBody?.containsKey("thinking") != true &&
            hasMatchingThinkingReplay(messages, replayOrigin)
        val effectiveThinking = reasoningEnabled || continuation
        val (system, mapped) = mapAnthropicMessages(messages, thinkingEnabled = effectiveThinking, replayOrigin = replayOrigin)
        // budget_tokens обязан быть < max_tokens: при включённом thinking
        // поднимаем потолок, иначе API 400 ("max_tokens must exceed budget").
        val thinking = thinkingPolicy.configuration(effectiveModelId, effectiveThinking)
        val requestDto = AnthropicMessagesRequest(
            model = effectiveModelId,
            maxTokens = if (effectiveThinking) THINKING_MAX_TOKENS else AnthropicMessagesRequest.DEFAULT_MAX_TOKENS,
            system = system,
            messages = mapped,
            tools = mapAnthropicTools(tools),
            stream = true,
            thinking = thinking,
        )
        val capture = ru.outsideproarts.oassist.core.provider.SseDiagnosticCapture(endpoint,
            diagnosticParent)
        try { http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(messagesUrl(endpoint.baseUrl))
                applyAuth(dialogKey = dialogKey(messages))
                if (thinking?.blockBinding != null) {
                    val existing = headers["anthropic-beta"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
                    headers.remove("anthropic-beta")
                    headers.append("anthropic-beta", (existing + THINKING_BINDING_BETA).distinct().joinToString(","))
                }
                contentType(ContentType.Application.Json)
                setBodyDecorated(endpoint, requestDto, diagnosticParent)
            },
        ) {
            val acc = AnthropicStreamAccumulator()
            val content = AnthropicContentAccumulator()
            var stopReason: String? = null
            var sawStop = false
            var callsFlushed = false
            var startUsage: AnthropicUsageDto? = null
            var deltaUsage: AnthropicUsageDto? = null
            val seenTransformations = mutableSetOf<AnthropicInputTransformationDto>()
            var lossNoticeSent = false
            suspend fun observeTransformations(items: List<AnthropicInputTransformationDto>) {
                val fresh = items.filter { it.type in setOf("thinking_dropped", "thinking_mismatch_allowed") && seenTransformations.add(it) }
                for (item in fresh) {
                    val reason = item.reason?.takeIf { it in setOf("prefix_binding_mismatch", "model_binding_mismatch", "organization_binding_mismatch") } ?: "other"
                    endpoint.diagnostics.emit(ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind.PROVIDER_RESPONSE,
                        parentOperationId = diagnosticParent?.operationId,
                        attributes = mapOf("stage" to "input_transformation", "type" to item.type.orEmpty(), "reason" to reason))
                }
                if (!lossNoticeSent && fresh.any { it.type == "thinking_dropped" }) {
                    lossNoticeSent = true
                    emit(ChatChunk.ProviderNotice(ChatChunk.ProviderNotice.Kind.THINKING_CONTEXT_REDUCED))
                }
            }
            // «Сколько осталось до ограничения» — из заголовков ответа.
            val rateLimits = RateLimitHeaders.parse(call.response.headers)
            suspend fun flushCalls() {
                if (callsFlushed) return
                callsFlushed = true
                acc.build().forEach { call ->
                    emit(ChatChunk.ToolCall(
                        id = call.id,
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                        index = call.index,
                    ))
                }
            }
            // Итоговый usage хода собирается один раз из message_start +
            // последнего message_delta (см. anthropicUsageOrNull: перекрытие
            // по полям, а не сумма — у агрегаторов delta несёт полную картину).
            fun currentUsage(): ProviderUsage? = anthropicUsageOrNull(startUsage, deltaUsage)
            // Сервер закрывает стрим сам; отдельного `[DONE]` у Anthropic нет —
            // финал приходит событием `message_stop`.
            incoming.collect { event ->
                val type = event.event ?: return@collect
                val data = event.data ?: return@collect
                capture.add(data)
                when (type) {
                    "message_start" -> {
                        val start = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicMessageStartEvent.serializer(),
                                data,
                            )
                        }.getOrNull()?.message
                        start?.usage?.let { startUsage = it }
                        observeTransformations(start?.inputTransformations.orEmpty())
                    }
                    "content_block_start" -> {
                        val start = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicContentBlockStartEvent.serializer(),
                                data,
                            )
                        }.getOrNull() ?: throw ProviderError.Protocol("anthropic: malformed content block start")
                        val block = ProviderJson.parseToJsonElement(data).jsonObject["content_block"]?.jsonObject
                            ?: throw ProviderError.Protocol("anthropic: missing content block")
                        content.start(start.index, block)
                        block["text"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ChatChunk.Content(it)) }
                        block["thinking"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ChatChunk.Reasoning(it)) }
                        acc.contentBlockStart(
                            start.index,
                            start.contentBlock?.type,
                            start.contentBlock?.id,
                            start.contentBlock?.name,
                            block["input"]?.toString(),
                        )
                    }
                    "content_block_delta" -> {
                        val deltaEvent = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicContentBlockDeltaEvent.serializer(),
                                data,
                            )
                        }.getOrNull() ?: throw ProviderError.Protocol("anthropic: malformed content block delta")
                        val delta = deltaEvent.delta ?: throw ProviderError.Protocol("anthropic: missing content block delta")
                        content.delta(deltaEvent.index, delta)
                        when (deltaEvent.delta?.type) {
                            "text_delta" -> deltaEvent.delta.text
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { emit(ChatChunk.Content(it)) }
                            "input_json_delta" -> deltaEvent.delta.partialJson
                                ?.let { acc.inputJsonDelta(deltaEvent.index, it) }
                            // «Мысли» модели: живой поток в UI + подпись для replay.
                            "thinking_delta" -> deltaEvent.delta.thinking
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { emit(ChatChunk.Reasoning(it)) }
                            "signature_delta" -> Unit // Kept per block, never attached to concatenated display text.
                            else -> {}
                        }
                    }
                    "content_block_stop" -> {
                        val index = ProviderJson.parseToJsonElement(data).jsonObject["index"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?: throw ProviderError.Protocol("anthropic: missing content block index")
                        content.stop(index)
                    }
                    "message_delta" -> {
                        val deltaEvent = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicMessageDeltaEvent.serializer(),
                                data,
                            )
                        }.getOrNull() ?: return@collect
                        deltaEvent.delta?.stopReason?.let { stopReason = it }
                        // Кумулятивный итог — берём последнее событие целиком.
                        deltaEvent.usage?.let { deltaUsage = it }
                        observeTransformations(deltaEvent.inputTransformations.orEmpty())
                    }
                    "message_stop" -> {
                        val blocks = content.complete()
                        if (blocks.any { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("thinking", "redacted_thinking") }) {
                            emit(ChatChunk.Reasoning("", anthropicReplay = ru.outsideproarts.oassist.core.model.AnthropicReplay(replayOrigin, blocks.toString())))
                        }
                        sawStop = true
                        flushCalls()
                        emit(ChatChunk.Finish(reason = stopReason ?: "end_turn"))
                    }
                    "error" -> {
                        val message = runCatching {
                            ProviderJson.decodeFromString(AnthropicErrorEvent.serializer(), data)
                        }.getOrNull()?.error?.message?.take(ERROR_MESSAGE_LIMIT)
                        throw ProviderError.Protocol("anthropic: ${message ?: "stream error"}")
                    }
                    else -> {}
                }
            }
            // Without message_stop, display deltas remain usable but calls and opaque
            // replay may be incomplete. Never execute or publish that partial protocol state.
            // Usage несёт только этот финальный Finish (message_stop — без него),
            // иначе аккумуляция в AgentLoop задвоила бы счётчики.
            if (sawStop) flushCalls()
            emit(ChatChunk.Finish(
                reason = stopReason ?: "end_turn",
                abrupt = !sawStop,
                usage = currentUsage().withRateLimits(rateLimits),
            ))
        } } finally { runCatching { capture.flush() } }
    }

    private fun HttpRequestBuilder.applyAuth(dialogKey: String) {
        // Размещение ключа — по схеме авторизации профиля (нативная — x-api-key).
        endpoint.applyAuthHeaders(this)
        if (!endpoint.auth.extraHeaders.containsKey(VERSION_HEADER)) {
            headers.append(VERSION_HEADER, ANTHROPIC_VERSION)
        }
        endpoint.decorateHeaders(this, dialogKey)
    }

    companion object {
        const val API_KEY_HEADER = "x-api-key"
        const val VERSION_HEADER = "anthropic-version"
        const val ANTHROPIC_VERSION = "2023-06-01"

        private const val ERROR_MESSAGE_LIMIT = 200
        private const val PROBE_TEXT = "hi"
        private const val PROBE_MAX_TOKENS = 1

        /** Native documented stopgap: the server reports any removed reasoning in the stream. */
        internal const val THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01"
        private const val THINKING_MAX_TOKENS = 8192

        private fun probeTool() = AnthropicToolDto(
            name = "probe",
            description = "probe",
            inputSchema = JsonObject(emptyMap()),
        )

        /** URL запросов: `{base}/messages` как есть (версия входит в baseUrl). */
        internal fun messagesUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/messages"

        suspend fun fromProfile(
            diagnostics: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder,
            profile: ProviderProfile,
            secretStore: SecretStore,
            httpClient: HttpClient,
            overrides: ProviderAuth? = null,
        ): AnthropicClient = AnthropicClient(
            EndpointConfig(
                diagnostics = diagnostics,
                protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                baseUrl = profile.baseUrl,
                auth = resolveProviderAuth(profile, secretStore, overrides),
                replayIdentity = profile.id + ":" + profile.credentialRevision.orEmpty(),
            ),
            httpClient,
        )
    }
}
