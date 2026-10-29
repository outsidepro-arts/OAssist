package ru.outsideproarts.oassist.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.request
import ru.outsideproarts.oassist.core.provider.setBodyDecorated
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
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.mergeUsage
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.provider.EndpointConfig
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.dialogKey
import ru.outsideproarts.oassist.core.provider.fetchModelCatalog
import ru.outsideproarts.oassist.core.provider.isProtocolUnsupportedMessage
import ru.outsideproarts.oassist.core.provider.resolveProviderAuth
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Нативный клиент Anthropic Messages API.
 * Auth — `x-api-key` + `anthropic-version` (не Bearer); `baseUrl` — хост
 * (`https://api.anthropic.com`) или с суффиксом `/v1`, удвоение не допускается.
 */
class AnthropicClient internal constructor(
    private val endpoint: EndpointConfig,
    private val http: HttpClient,
) : ModelClient {

    override suspend fun fetchModels(): List<ModelInfo> = fetchModelCatalog(endpoint, http)

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
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
                setBodyDecorated(endpoint, requestDto)
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
        val (system, mapped) = mapAnthropicMessages(messages, thinkingEnabled = reasoningEnabled)
        // budget_tokens обязан быть < max_tokens: при включённом thinking
        // поднимаем потолок, иначе API 400 ("max_tokens must exceed budget").
        val thinking = if (reasoningEnabled) {
            AnthropicThinkingDto(type = "enabled", budgetTokens = THINKING_BUDGET_TOKENS)
        } else {
            null
        }
        val requestDto = AnthropicMessagesRequest(
            model = modelId,
            maxTokens = if (thinking != null) THINKING_MAX_TOKENS else AnthropicMessagesRequest.DEFAULT_MAX_TOKENS,
            system = system,
            messages = mapped,
            tools = mapAnthropicTools(tools),
            stream = true,
            thinking = thinking,
        )
        http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(messagesUrl(endpoint.baseUrl))
                applyAuth(dialogKey = dialogKey(messages))
                contentType(ContentType.Application.Json)
                setBodyDecorated(endpoint, requestDto)
            },
        ) {
            val acc = AnthropicStreamAccumulator()
            var stopReason: String? = null
            var sawStop = false
            var callsFlushed = false
            var inputUsage: ProviderUsage? = null
            var outputTokens: Long? = null
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
            // Итоговый usage хода: input из message_start, output — кумулятивный
            // из message_delta (перезапись, не сумма). Строится через mergeUsage
            // ради восстановления totalTokens.
            fun currentUsage(): ProviderUsage? = mergeUsage(
                inputUsage,
                ProviderUsage.of(outputTokens = outputTokens),
            )
            // Сервер закрывает стрим сам; отдельного `[DONE]` у Anthropic нет —
            // финал приходит событием `message_stop`.
            incoming.collect { event ->
                val type = event.event ?: return@collect
                val data = event.data ?: return@collect
                when (type) {
                    "message_start" -> {
                        runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicMessageStartEvent.serializer(),
                                data,
                            )
                        }.getOrNull()?.message?.usage?.let { u ->
                            inputUsage = ProviderUsage.of(
                                inputTokens = u.inputTokens,
                                cachedTokens = u.cacheReadInputTokens,
                            )
                        }
                    }
                    "content_block_start" -> {
                        val start = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicContentBlockStartEvent.serializer(),
                                data,
                            )
                        }.getOrNull() ?: return@collect
                        acc.contentBlockStart(
                            start.index,
                            start.contentBlock?.type,
                            start.contentBlock?.id,
                            start.contentBlock?.name,
                        )
                    }
                    "content_block_delta" -> {
                        val deltaEvent = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicContentBlockDeltaEvent.serializer(),
                                data,
                            )
                        }.getOrNull() ?: return@collect
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
                            "signature_delta" -> deltaEvent.delta.signature
                                ?.let { emit(ChatChunk.Reasoning(delta = "", signature = it)) }
                            else -> {}
                        }
                    }
                    "message_delta" -> {
                        val deltaEvent = runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicMessageDeltaEvent.serializer(),
                                data,
                            )
                        }.getOrNull() ?: return@collect
                        deltaEvent.delta?.stopReason?.let { stopReason = it }
                        // Кумулятивный итог — берём последнее значение.
                        deltaEvent.usage?.outputTokens?.let { outputTokens = it }
                    }
                    "message_stop" -> {
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
            // Страховка: соединение закрылось без `message_stop` — отдать накопленное.
            // Без терминатора ответ мог быть усечён — помечаем abrupt.
            // Usage несёт только этот финальный Finish (message_stop — без него),
            // иначе аккумуляция в AgentLoop задвоила бы счётчики.
            flushCalls()
            emit(ChatChunk.Finish(reason = stopReason ?: "end_turn", abrupt = !sawStop, usage = currentUsage()))
        }
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

        /** Бюджет extended thinking; с [THINKING_MAX_TOKENS] выполняется budget < max_tokens. */
        private const val THINKING_BUDGET_TOKENS = 4096
        private const val THINKING_MAX_TOKENS = 8192

        private fun probeTool() = AnthropicToolDto(
            name = "probe",
            description = "probe",
            inputSchema = JsonObject(emptyMap()),
        )

        /** URL запросов: `{base}/messages` как есть (версия входит в baseUrl). */
        internal fun messagesUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/messages"

        suspend fun fromProfile(
            profile: ProviderProfile,
            secretStore: SecretStore,
            httpClient: HttpClient,
            overrides: ProviderAuth? = null,
        ): AnthropicClient = AnthropicClient(
            EndpointConfig(
                protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                baseUrl = profile.baseUrl,
                auth = resolveProviderAuth(profile, secretStore, overrides),
            ),
            httpClient,
        )
    }
}
