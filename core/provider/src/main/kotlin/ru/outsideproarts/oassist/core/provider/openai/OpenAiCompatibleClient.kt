package ru.outsideproarts.oassist.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.ProviderJson

class OpenAiCompatibleClient private constructor(
    private val profile: ProviderProfile,
    private val auth: ProviderAuth,
    private val http: HttpClient,
) : ModelClient {

    private val isZen: Boolean = profile.kind == ProviderKind.OPENCODE_ZEN

    override suspend fun fetchModels(): List<ModelInfo> {
        val modelsUrl = URLBuilder(profile.baseUrl).apply {
            appendPathSegments("models")
        }.buildString()
        val response = http.request {
            method = HttpMethod.Get
            url.takeFrom(modelsUrl)
            applyAuth(zenSessionId = if (isZen) OpenCodeZen.sessionId(profile.baseUrl) else null)
        }
        val dto = ProviderJson.decodeFromString(OpenAiModelsResponseDto.serializer(), response.bodyAsText())
        return dto.data.map { ModelInfo(id = it.id) }
    }

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
        val chatUrl = URLBuilder(profile.baseUrl).apply {
            appendPathSegments("chat", "completions")
        }.buildString()
        val requestDto = ChatRequestDto(
            model = modelId,
            messages = listOf(ChatMessageDto(role = "user", content = JsonPrimitive(PROBE_TEXT))),
            stream = false,
            maxTokens = PROBE_MAX_TOKENS,
            tools = listOf(probeTool()),
        )
        try {
            val response = http.request {
                method = HttpMethod.Post
                url.takeFrom(chatUrl)
                applyAuth(zenSessionId = if (isZen) OpenCodeZen.sessionId(dialogHead(emptyList())) else null)
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            }
            ProviderJson.decodeFromString(ChatResponseDto.serializer(), response.bodyAsText())
            return setOf(ModelCapability.TOOLS)
        } catch (e: ResponseException) {
            // 400 — сервер не принял `tools` (модель без инструментов или строгий
            // гейт): это сигнал, а не ошибка. 401/404/сеть — пробрасываются выше.
            if (e.response.status == HttpStatusCode.BadRequest) return emptySet()
            throw e
        }
    }

    override fun chatCompletionStream(
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchemaDto>,
    ): Flow<ChatChunk> = flow {
        val chatUrl = URLBuilder(profile.baseUrl).apply {
            appendPathSegments("chat", "completions")
        }.buildString()
        val zenSessionId = if (isZen) OpenCodeZen.sessionId(dialogHead(messages)) else null
        val requestDto = ChatRequestDto(
            model = modelId,
            messages = messages.map { it.toDto() },
            stream = true,
            tools = tools.ifEmpty { null },
        )
        http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(chatUrl)
                applyAuth(zenSessionId)
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            },
        ) {
            val assembler = ToolCallAssembler()
            var callsFlushed = false
            suspend fun flushCalls() {
                if (callsFlushed) return
                callsFlushed = true
                assembler.build().forEach { call ->
                    emit(ChatChunk.ToolCall(
                        id = call.id,
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                        index = call.index,
                    ))
                }
            }
            // DONE отменяет апстрим: соединение закрываем сами, а не ждём сервер.
            // Иначе при висящем соединении (кипэлайвы/прокси) стрим не завершится никогда.
            incoming.takeWhile { event -> event.data != DONE_SENTINEL }.collect { event ->
                val data = event.data ?: return@collect
                val chunk = runCatching {
                    ProviderJson.decodeFromString(ChatResponseDto.serializer(), data)
                }.getOrNull() ?: return@collect
                val delta = chunk.choices.firstOrNull()?.delta
                delta?.content?.let { content ->
                    if (content.isNotEmpty()) emit(ChatChunk.Content(content))
                }
                delta?.toolCalls?.forEach { assembler.feed(it) }
                val finish = chunk.choices.firstOrNull()?.finishReason
                if (finish != null) {
                    flushCalls()
                    emit(ChatChunk.Finish(reason = finish))
                }
            }
            // Страховка: сервер закрыл соединение без DONE/finish — отдать накопленное.
            flushCalls()
            emit(ChatChunk.Finish(reason = "stop"))
        }
    }

    private fun HttpRequestBuilder.applyAuth(zenSessionId: String? = null) {
        // Basic — для своего `opencode serve` за логином/паролем; при его наличии
        // Bearer не шлётся (серверу нужен ровно один `Authorization`).
        val basic = basicHeader()
        if (basic != null) {
            headers.append(HttpHeaders.Authorization, basic)
        } else {
            auth.bearerToken?.let {
                headers.append(HttpHeaders.Authorization, "Bearer " + String(it))
            }
        }
        if (zenSessionId != null) {
            headers.append(OpenCodeZen.SESSION_HEADER, zenSessionId)
            headers.append(OpenCodeZen.REQUEST_HEADER, OpenCodeZen.requestId())
        }
        auth.extraHeaders.forEach { (name, value) ->
            headers.append(name, value)
        }
    }

    /** `Basic base64(user:password)` или null, если пара неполная. */
    private fun basicHeader(): String? {
        val user = auth.basicUser ?: return null
        val password = auth.basicPassword ?: return null
        val raw = String(user) + ":" + String(password)
        return "Basic " + kotlin.io.encoding.Base64.encode(raw.toByteArray())
    }

    companion object {
        private const val DONE_SENTINEL = "[DONE]"

        /** Текст пробного запроса: короткий, без персональных данных. */
        private const val PROBE_TEXT = "hi"
        private const val PROBE_MAX_TOKENS = 1

        /** Фиктивный инструмент пробы: имя/описание только для wire-формата. */
        private fun probeTool() = ToolSchemaDto(
            function = ToolFunctionSchemaDto(
                name = "probe",
                description = "probe",
                parameters = buildJsonObject { put("type", "object") },
            ),
        )

        /** «Голова» диалога: первое system-сообщение + первый user-вопрос. */
        internal fun dialogHead(messages: List<ChatMessage>): String {
            val system = messages.firstOrNull { it.role == ChatRole.SYSTEM }?.content.orEmpty()
            val firstUser = messages.firstOrNull { it.role == ChatRole.USER }?.content.orEmpty()
            return "$system\u0000$firstUser"
        }

        suspend fun fromProfile(
            profile: ProviderProfile,
            secretStore: SecretStore,
            httpClient: HttpClient,
            overrides: ProviderAuth? = null,
        ): OpenAiCompatibleClient {
            val apiKey = overrides?.bearerToken ?: profile.apiKeyRef?.let { secretStore.get(it) }
            val basicUser = overrides?.basicUser ?: profile.basicAuthUserRef?.let { secretStore.get(it) }
            val basicPassword = overrides?.basicPassword ?: profile.basicAuthPasswordRef?.let { secretStore.get(it) }
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

            val auth = ProviderAuth(
                bearerToken = apiKey,
                basicUser = basicUser,
                basicPassword = basicPassword,
                extraHeaders = extras,
            )
            return OpenAiCompatibleClient(profile, auth, httpClient)
        }

        private fun ChatRole.toWire(): String = when (this) {
            ChatRole.SYSTEM -> "system"
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
            ChatRole.TOOL -> "tool"
        }

        internal fun ChatMessage.toDto(): ChatMessageDto = ChatMessageDto(
            role = role.toWire(),
            // Пустой assistant-текст с tool_calls — как null (строгость серверов различается).
            // Голосовой ход — массивом parts (текст + input_audio), иначе строкой.
            content = audio?.let { audioPart ->
                buildJsonArray {
                    if (content.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", content)
                        })
                    }
                    add(buildJsonObject {
                        put("type", "input_audio")
                        put("input_audio", buildJsonObject {
                            put("data", audioPart.wavBase64)
                            put("format", "wav")
                        })
                    })
                }
            } ?: content.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) },
            toolCallId = toolCallId,
            toolCalls = toolCalls?.map { call ->
                ToolCallRequestDto(
                    id = call.id,
                    function = ToolCallRequestFunctionDto(
                        name = call.name,
                        arguments = call.argumentsJson,
                    ),
                )
            },
        )
    }
}