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

/** Запрос POST /v1/messages. `content` — строка или массив блоков (см. маппер).
 *  [maxTokens] и [stream] — БЕЗ дефолтов: encodeDefaults=false не пишет
 *  равное-дефолту значение, а API требует `max_tokens`, а SSE — `stream` (урок
 *  StreamOptionsDto); оба поля обязаны всегда попадать в wire. */
@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicMessageDto>,
    val tools: List<AnthropicToolDto>? = null,
    val stream: Boolean,
    /** Extended thinking: только при флаге REASONING у модели; null-поле не сериализуется. */
    val thinking: AnthropicThinkingDto? = null,
) {
    companion object {
        /** Anthropic требует max_tokens; дефолт покрывает ответы с тулами. */
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/** Включённый extended thinking: budget < max_tokens запроса (см. клиента).
 *  [type] — без дефолта: encodeDefaults=false не пишет равное-дефолту значение,
 *  а API требует `"type": "enabled"` (урок StreamOptionsDto). */
@Serializable
data class AnthropicThinkingDto(
    val type: String,
    @SerialName("budget_tokens") val budgetTokens: Int,
)

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
    /** `thinking_delta`: кусок «мышлений» (только при включённом thinking). */
    val thinking: String? = null,
    /** `signature_delta`: подпись thinking-блока — обязательна при replay. */
    val signature: String? = null,
)

@Serializable
data class AnthropicMessageDeltaEvent(
    val type: String? = null,
    val delta: AnthropicDelta? = null,
    /** Кумулятивный output_tokens на конец хода (приходит только здесь). */
    val usage: AnthropicUsageDto? = null,
)

/** Usage-поля Anthropic: input — в message_start, output — кумулятивно в message_delta. */
@Serializable
data class AnthropicUsageDto(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
)

/** Первое событие потока: несёт message с usage (input_tokens известны сразу). */
@Serializable
data class AnthropicMessageStartEvent(
    val type: String? = null,
    val message: AnthropicMessageStartBody? = null,
)

@Serializable
data class AnthropicMessageStartBody(
    val usage: AnthropicUsageDto? = null,
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
