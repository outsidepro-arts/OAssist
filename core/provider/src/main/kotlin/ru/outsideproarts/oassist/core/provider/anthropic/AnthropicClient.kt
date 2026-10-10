package ru.outsideproarts.oassist.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Нативный клиент Anthropic Messages API.
 * Auth — `x-api-key` + `anthropic-version` (не Bearer); `baseUrl` — хост
 * (`https://api.anthropic.com`) или с суффиксом `/v1`, удвоение не допускается.
 */
class AnthropicClient private constructor(
    private val profile: ProviderProfile,
    private val auth: ProviderAuth,
    private val http: HttpClient,
) : ModelClient {

    override suspend fun fetchModels(): List<ModelInfo> {
        val response = http.request {
            method = HttpMethod.Get
            url.takeFrom(modelsUrl(profile.baseUrl))
            applyAuth()
        }
        val dto = ProviderJson.decodeFromString(
            AnthropicModelsResponseDto.serializer(),
            response.bodyAsText(),
        )
        return dto.data.map { ModelInfo(id = it.id, label = it.displayName) }
    }

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
                url.takeFrom(messagesUrl(profile.baseUrl))
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            }
            ProviderJson.decodeFromString(
                AnthropicMessageResponseDto.serializer(),
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
        val (system, mapped) = mapAnthropicMessages(messages)
        val requestDto = AnthropicMessagesRequest(
            model = modelId,
            system = system,
            messages = mapped,
            tools = mapAnthropicTools(tools),
        )
        http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(messagesUrl(profile.baseUrl))
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            },
        ) {
            val acc = AnthropicStreamAccumulator()
            var stopReason: String? = null
            var sawStop = false
            var callsFlushed = false
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
            // Сервер закрывает стрим сам; отдельного `[DONE]` у Anthropic нет —
            // финал приходит событием `message_stop`.
            incoming.collect { event ->
                val type = event.event ?: return@collect
                val data = event.data ?: return@collect
                when (type) {
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
                            else -> {}
                        }
                    }
                    "message_delta" -> {
                        runCatching {
                            ProviderJson.decodeFromString(
                                AnthropicMessageDeltaEvent.serializer(),
                                data,
                            )
                        }.getOrNull()?.delta?.stopReason?.let { stopReason = it }
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
            flushCalls()
            emit(ChatChunk.Finish(reason = stopReason ?: "end_turn", abrupt = !sawStop))
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        auth.bearerToken?.let {
            headers.append(API_KEY_HEADER, String(it))
        }
        if (!auth.extraHeaders.containsKey(VERSION_HEADER)) {
            headers.append(VERSION_HEADER, ANTHROPIC_VERSION)
        }
        auth.extraHeaders.forEach { (name, value) ->
            headers.append(name, value)
        }
    }

    companion object {
        const val API_KEY_HEADER = "x-api-key"
        const val VERSION_HEADER = "anthropic-version"
        const val ANTHROPIC_VERSION = "2023-06-01"

        private const val ERROR_MESSAGE_LIMIT = 200
        private const val PROBE_TEXT = "hi"
        private const val PROBE_MAX_TOKENS = 1

        private fun probeTool() = AnthropicToolDto(
            name = "probe",
            description = "probe",
            inputSchema = JsonObject(emptyMap()),
        )

        /**
         * URL списка моделей: `{base}/v1/models`. Хвост `/v1` в base терпится,
         * чтобы не удваивать (`.../v1/v1/models`).
         */
        internal fun modelsUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        }

        internal fun messagesUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }

        suspend fun fromProfile(
            profile: ProviderProfile,
            secretStore: SecretStore,
            httpClient: HttpClient,
            overrides: ProviderAuth? = null,
        ): AnthropicClient {
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
            return AnthropicClient(
                profile,
                ProviderAuth(bearerToken = apiKey, extraHeaders = extras),
                httpClient,
            )
        }
    }
}
