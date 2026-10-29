package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import ru.outsideproarts.oassist.core.provider.mapProviderUsage
import ru.outsideproarts.oassist.core.provider.opencode.OpenCodeUsageExtensionDto

/**
 * Сообщение чата или дельта. В потоке дельты поля [role]/[content] могут частично
 * отсутствовать, поэтому оба nullable.
 * `content` полиморфен, как у сервера: строка текста или массив content-parts
 * (текст + `input_audio` для голосовых ходов).
 */
@Serializable
data class ChatMessageDto(
    val role: String? = null,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallRequestDto>? = null,
)

/** Вызов инструмента в исходящем assistant-сообщении (пара к будущим tool-ответам). */
@Serializable
data class ToolCallRequestDto(
    val id: String,
    val type: String = "function",
    val function: ToolCallRequestFunctionDto,
)

@Serializable
data class ToolCallRequestFunctionDto(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val tools: List<ToolSchemaDto>? = null,
    /** Только для стрима: просим usage-чанк с пустым choices после finish_reason. */
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)

@Serializable
data class StreamOptionsDto(
    // Без дефолта: kotlinx не кодирует равный-дефолту true (encodeDefaults=false)
    // и наружу улетал бы пустой `{}` — usage включался бы молча.
    @SerialName("include_usage") val includeUsage: Boolean,
)

@Serializable
data class ToolSchemaDto(
    val type: String = "function",
    val function: ToolFunctionSchemaDto,
)

@Serializable
data class ToolFunctionSchemaDto(
    val name: String,
    val description: String? = null,
    val parameters: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class ChatResponseDto(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val model: String? = null,
    val choices: List<ChatChoiceDto> = emptyList(),
    val usage: ChatUsageDto? = null,
    /** Нестандартное поле Zen/прокси: стоимость запроса в USD. Стандартный OpenAI не шлёт. */
    val cost: Double? = null,
)

@Serializable
data class ChatUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: ChatPromptTokensDetailsDto? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: ChatCompletionTokensDetailsDto? = null,
    /** Стоимость запроса в USD внутри usage (OpenCode/прокси); стандартный OpenAI не шлёт. */
    val cost: Double? = null,
    /** Расширенная статистика OpenCode (billing_source/list_cost/allowance/tokens). */
    val opencode: OpenCodeUsageExtensionDto? = null,
)

@Serializable
data class ChatPromptTokensDetailsDto(
    @SerialName("cached_tokens") val cachedTokens: Long? = null,
)

@Serializable
data class ChatCompletionTokensDetailsDto(
    /** Токены «мышления» — биллятся как output, в текст ответа не входят. */
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
)

/**
 * Usage/cost из ответа в каноническую модель. Cost — нестандартное поле
 * Zen/прокси: внутри `usage` (OpenCode) либо top-level (легаси), стандартный
 * OpenAI его не шлёт — null честнее выдумок.
 */
internal fun ChatResponseDto.usageOrCost(): ru.outsideproarts.oassist.core.model.ProviderUsage? {
    if (usage == null && cost == null) return null
    return mapProviderUsage(
        inputTokens = usage?.promptTokens,
        outputTokens = usage?.completionTokens,
        totalTokens = usage?.totalTokens,
        cachedTokens = usage?.promptTokensDetails?.cachedTokens,
        reasoningTokens = usage?.completionTokensDetails?.reasoningTokens,
        costUsd = usage?.cost ?: cost,
        openCode = usage?.opencode,
    )
}

@Serializable
data class ChatChoiceDto(
    val index: Int = 0,
    val message: ChatMessageDto? = null,
    val delta: ChatDeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatDeltaDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDeltaDto>? = null,
    /** Ход «мышлений» DeepSeek и совместимых — в текст ответа не попадает. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

@Serializable
data class ToolCallDeltaDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunctionDto? = null,
)

@Serializable
data class ToolCallFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)