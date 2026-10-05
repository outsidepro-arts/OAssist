package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Ответ GET /v1/models. Лишние поля игнорируются lenient-парсером. */
@Serializable
data class AnthropicModelsResponseDto(
    val data: List<AnthropicModelDto> = emptyList(),
)

@Serializable
data class AnthropicModelDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

/** Запрос POST /v1/messages. `content` — строка или массив блоков (см. маппер). */
@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val system: String? = null,
    val messages: List<AnthropicMessageDto>,
    val tools: List<AnthropicToolDto>? = null,
    val stream: Boolean = true,
) {
    companion object {
        /** Anthropic требует max_tokens; дефолт покрывает ответы с тулами. */
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

@Serializable
data class AnthropicMessageDto(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class AnthropicToolDto(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

/** События SSE-потока /v1/messages: тип — в поле `event`, payload — в `data`. */
@Serializable
data class AnthropicContentBlockStartEvent(
    val type: String? = null,
    val index: Int = 0,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
)

@Serializable
data class AnthropicContentBlock(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class AnthropicContentBlockDeltaEvent(
    val type: String? = null,
    val index: Int = 0,
    val delta: AnthropicDelta? = null,
)

@Serializable
data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicMessageDeltaEvent(
    val type: String? = null,
    val delta: AnthropicDelta? = null,
)

@Serializable
data class AnthropicErrorEvent(
    val type: String? = null,
    val error: AnthropicErrorBody? = null,
)

@Serializable
data class AnthropicErrorBody(
    val type: String? = null,
    val message: String? = null,
)

/** Ответ нестримового POST /v1/messages (для пробы достаточно формы). */
@Serializable
data class AnthropicMessageResponseDto(
    val type: String? = null,
    val content: List<AnthropicContentBlock>? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)
