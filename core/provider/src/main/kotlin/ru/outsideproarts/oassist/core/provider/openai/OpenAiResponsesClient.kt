package ru.outsideproarts.oassist.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.mergeUsage
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.EndpointConfig
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.detectStreamErrorMessage
import ru.outsideproarts.oassist.core.provider.dialogKey
import ru.outsideproarts.oassist.core.provider.isProtocolUnsupportedMessage

/**
 * Нативный клиент OpenAI Responses API (`POST {base}/responses`).
 * Auth — `Authorization: Bearer` (как у chat/completions); `baseUrl` включает
 * `/v1` (`https://api.openai.com/v1`, `https://opencode.ai/zen/v1`).
 * Нативный протокол GPT/Grok на агрегаторах; в `tools` — плоская форма схем.
 */
class OpenAiResponsesClient internal constructor(
    private val endpoint: EndpointConfig,
    private val http: HttpClient,
) : ModelClient {

    override suspend fun fetchModels(): List<ModelInfo> {
        val modelsUrl = URLBuilder(endpoint.baseUrl).apply {
            appendPathSegments("models")
        }.buildString()
        val response = http.request {
            method = HttpMethod.Get
            url.takeFrom(modelsUrl)
            applyAuth(dialogKey = endpoint.baseUrl)
        }
        val dto = ProviderJson.decodeFromString(OpenAiModelsResponseDto.serializer(), response.bodyAsText())
        return dto.data.map { ModelInfo(id = it.id) }
    }

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
        val requestDto = ResponsesRequestDto(
            model = modelId,
            input = listOf(userTextItem(PROBE_TEXT)),
            stream = false,
            tools = listOf(probeTool()),
            store = false,
            maxOutputTokens = PROBE_MAX_TOKENS,
        )
        try {
            val response = http.request {
                method = HttpMethod.Post
                url.takeFrom(responsesUrl())
                applyAuth(dialogKey = dialogKey(emptyList()))
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            }
            ProviderJson.decodeFromString(ResponsesStreamEventDto.serializer(), response.bodyAsText())
            return setOf(ModelCapability.TOOLS)
        } catch (e: ResponseException) {
            // 400 — сервер не принял `tools`: сигнал, а не ошибка.
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
    ): Flow<ChatChunk> = flow {
        val (instructions, input) = mapResponsesInput(messages)
        val requestDto = ResponsesRequestDto(
            model = modelId,
            instructions = instructions,
            input = input,
            stream = true,
            tools = tools.map { it.toResponsesTool() }.ifEmpty { null },
            store = false,
        )
        http.sse(
            request = {
                method = HttpMethod.Post
                url.takeFrom(responsesUrl())
                applyAuth(dialogKey = dialogKey(messages))
                contentType(ContentType.Application.Json)
                setBody(requestDto)
            },
        ) {
            val pending = mutableMapOf<String, PendingCall>()
            var lastFinish: String? = null
            var usage: ProviderUsage? = null
            incoming.collect { event ->
                val data = event.data ?: return@collect
                val dto = runCatching {
                    ProviderJson.decodeFromString(ResponsesStreamEventDto.serializer(), data)
                }.getOrNull()
                if (dto == null) {
                    detectStreamErrorMessage(data)
                        ?.let { throw ProviderError.Protocol("openai-responses: $it") }
                    return@collect
                }
                when (dto.type) {
                    "response.output_text.delta" -> dto.delta
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(ChatChunk.Content(it)) }
                    "response.output_item.added" -> {
                        val item = dto.item
                        if (item?.type == "function_call") {
                            pending[item.id.orEmpty()] = PendingCall(
                                callId = item.callId ?: item.id.orEmpty(),
                                name = item.name.orEmpty(),
                                args = item.arguments.orEmpty(),
                            )
                        }
                    }
                    "response.function_call_arguments.delta" -> {
                        val call = pending[dto.itemId.orEmpty()] ?: return@collect
                        call.args += dto.delta.orEmpty()
                    }
                    "response.function_call_arguments.done" -> {
                        val call = pending[dto.itemId.orEmpty()] ?: return@collect
                        if (!call.emitted) {
                            call.emitted = true
                            emit(ChatChunk.ToolCall(
                                id = call.callId,
                                name = call.name,
                                argumentsJson = dto.arguments ?: call.args,
                            ))
                        }
                    }
                    "response.output_item.done" -> {
                        val item = dto.item ?: return@collect
                        if (item.type == "function_call") {
                            val call = pending[item.id.orEmpty()]
                            if (call == null || !call.emitted) {
                                emit(ChatChunk.ToolCall(
                                    id = item.callId ?: call?.callId ?: item.id.orEmpty(),
                                    name = item.name ?: call?.name.orEmpty(),
                                    argumentsJson = item.arguments ?: call?.args.orEmpty(),
                                ))
                            }
                        }
                    }
                    "response.completed" -> {
                        lastFinish = "stop"
                        usage = mergeUsage(usage, dto.response?.usage?.toProviderUsage())
                        emit(ChatChunk.Finish(reason = "stop"))
                    }
                    "response.incomplete" -> {
                        val reason = dto.response?.incompleteDetails?.reason ?: "length"
                        lastFinish = reason
                        usage = mergeUsage(usage, dto.response?.usage?.toProviderUsage())
                        emit(ChatChunk.Finish(reason = reason))
                    }
                    "response.failed" -> {
                        val message = dto.response?.error?.message?.take(ERROR_MESSAGE_LIMIT)
                        throw ProviderError.Protocol("openai-responses: ${message ?: "stream failed"}")
                    }
                    "error" -> {
                        val message = dto.message?.take(ERROR_MESSAGE_LIMIT)
                        throw ProviderError.Protocol("openai-responses: ${message ?: "stream error"}")
                    }
                    else -> {}
                }
            }
            // Страховка: соединение закрылось без completed/incomplete — ответ мог
            // быть усечён, помечаем abrupt. Usage несёт только финальный Finish
            // (событийные — без него), иначе аккумуляция в AgentLoop задвоила бы.
            emit(ChatChunk.Finish(reason = lastFinish ?: "stop", abrupt = lastFinish == null, usage = usage))
        }
    }

    private fun responsesUrl(): String = URLBuilder(endpoint.baseUrl).apply {
        appendPathSegments("responses")
    }.buildString()

    private fun HttpRequestBuilder.applyAuth(dialogKey: String) {
        endpoint.auth.bearerToken?.let {
            headers.append(HttpHeaders.Authorization, "Bearer " + String(it))
        }
        endpoint.decorateHeaders(this, dialogKey)
    }

    private data class PendingCall(
        val callId: String,
        val name: String,
        var args: String,
        var emitted: Boolean = false,
    )

    companion object {
        private const val ERROR_MESSAGE_LIMIT = 200
        private const val PROBE_TEXT = "hi"
        private const val PROBE_MAX_TOKENS = 1

        private fun probeTool() = ResponsesToolDto(
            name = "probe",
            description = "probe",
            parameters = buildJsonObject { put("type", "object") },
        )

        /** Текстовый user-элемент (пробы и простых ходов). */
        internal fun userTextItem(text: String): JsonElement = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            })
        }

        /**
         * История в input Responses: SYSTEM → `instructions`, user/assistant —
         * message-элементы, вызовы — `function_call`, результаты —
         * `function_call_output`. Tool-образы следуют отдельным user-элементом
         * (как у chat/completions: у tool-вывода только строковый output).
         */
        internal fun mapResponsesInput(messages: List<ChatMessage>): Pair<String?, List<JsonElement>> {
            val instructions = messages.filter { it.role == ChatRole.SYSTEM }
                .joinToString("\n\n") { it.content }
                .takeIf { it.isNotBlank() }
            val items = mutableListOf<JsonElement>()
            for (message in messages) {
                when (message.role) {
                    ChatRole.SYSTEM -> {}
                    ChatRole.USER -> items += userMessageItem(message)
                    ChatRole.ASSISTANT -> {
                        if (message.content.isNotEmpty()) {
                            items += buildJsonObject {
                                put("role", "assistant")
                                put("content", message.content)
                            }
                        }
                        message.toolCalls?.forEach { call ->
                            items += buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        }
                    }
                    ChatRole.TOOL -> {
                        items += buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", message.toolCallId.orEmpty())
                            put("output", message.content)
                        }
                        if (message.images.isNotEmpty()) {
                            items += userImagesItem("Изображения от инструмента:", message.images)
                        }
                    }
                }
            }
            return instructions to items
        }

        private fun userMessageItem(message: ChatMessage): JsonElement = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                if (message.content.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", message.content)
                    })
                }
                message.audio?.let { audio ->
                    add(buildJsonObject {
                        put("type", "input_audio")
                        put("input_audio", buildJsonObject {
                            put("data", audio.wavBase64)
                            put("format", "wav")
                        })
                    })
                }
                message.images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", ImageCodec.dataUri(img))
                    })
                }
            })
        }

        private fun userImagesItem(text: String, images: List<ru.outsideproarts.oassist.core.model.ImagePart>): JsonElement =
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    })
                    images.forEach { img ->
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", ImageCodec.dataUri(img))
                        })
                    }
                })
            }
    }
}

/** Схема chat/completions → плоская форма Responses. */
internal fun ToolSchemaDto.toResponsesTool(): ResponsesToolDto = ResponsesToolDto(
    name = function.name,
    description = function.description,
    parameters = function.parameters,
)
