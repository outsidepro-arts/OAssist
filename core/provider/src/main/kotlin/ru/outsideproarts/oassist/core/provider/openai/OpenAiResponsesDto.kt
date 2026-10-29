package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.provider.mapProviderUsage
import ru.outsideproarts.oassist.core.provider.opencode.OpenCodeUsageExtensionDto

/**
 * DTO OpenAI Responses API (`POST /responses`) — четвёртый проводной протокол
 * (нативный для GPT/Grok на агрегаторах вроде OpenCode Zen). Input полиморфен
 * (message / function_call / function_call_output) — вход строится JsonElement'ами,
 * DTO ниже — только однородные вектора.
 */
@Serializable
data class ResponsesRequestDto(
    val model: String,
    val instructions: String? = null,
    val input: List<JsonElement>,
    val stream: Boolean = false,
    val tools: List<ResponsesToolDto>? = null,
    /** Не сохранять ответы на стороне OpenAI (приватность); совместимые шлюзы поле игнорируют. */
    val store: Boolean? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    /** Сводка мыслей в стриме (события `response.reasoning_summary_text.delta`). */
    val reasoning: ResponsesReasoningDto? = null,
    /** Контекст повторного использования: encrypted reasoning-item'ы для stateless-продолжений. */
    val include: List<String>? = null,
)

/** Без дефолта у [summary]: encodeDefaults=false не пишет равное-дефолту значение. */
@Serializable
data class ResponsesReasoningDto(
    val summary: String,
)

/** Схема инструмента Responses: плоская (не `function: {...}` как в chat/completions). */
@Serializable
data class ResponsesToolDto(
    val type: String = "function",
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

/** SSE-событие стрима: тип + только нужные нам поля (lenient ко всем остальным). */
@Serializable
data class ResponsesStreamEventDto(
    val type: String? = null,
    val delta: String? = null,
    val arguments: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val item: ResponsesOutputItemDto? = null,
    val response: ResponsesResponseDto? = null,
    val message: String? = null,
)

/** Элемент вывода: message или function_call. */
@Serializable
data class ResponsesOutputItemDto(
    val id: String? = null,
    val type: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class ResponsesResponseDto(
    val id: String? = null,
    val status: String? = null,
    val usage: ResponsesUsageDto? = null,
    val error: ResponsesErrorDto? = null,
    @SerialName("incomplete_details") val incompleteDetails: ResponsesIncompleteDetailsDto? = null,
)

@Serializable
data class ResponsesUsageDto(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: ResponsesInputTokensDetailsDto? = null,
    @SerialName("output_tokens_details") val outputTokensDetails: ResponsesOutputTokensDetailsDto? = null,
    /** Стоимость запроса в USD (OpenCode/прокси); стандартный OpenAI не шлёт. */
    val cost: Double? = null,
    /** Расширенная статистика OpenCode (billing_source/list_cost/allowance/tokens). */
    val opencode: OpenCodeUsageExtensionDto? = null,
)

@Serializable
data class ResponsesInputTokensDetailsDto(
    @SerialName("cached_tokens") val cachedTokens: Long? = null,
    /** Запись в prompt-кэш (TTL 30m/12h у OpenAI); биллится отдельным тарифом. */
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long? = null,
)

@Serializable
data class ResponsesOutputTokensDetailsDto(
    /** Токены «мышления» — биллятся как output, в текст ответа не входят. */
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
)

@Serializable
data class ResponsesErrorDto(
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class ResponsesIncompleteDetailsDto(
    val reason: String? = null,
)

/** Usage Responses в каноническую модель (включая cost/opencode у агрегаторов). */
internal fun ResponsesUsageDto.toProviderUsage(): ProviderUsage? = mapProviderUsage(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTokens = totalTokens,
    cachedTokens = inputTokensDetails?.cachedTokens,
    cacheWriteTokens = inputTokensDetails?.cacheWriteTokens,
    reasoningTokens = outputTokensDetails?.reasoningTokens,
    costUsd = cost,
    openCode = opencode,
)
