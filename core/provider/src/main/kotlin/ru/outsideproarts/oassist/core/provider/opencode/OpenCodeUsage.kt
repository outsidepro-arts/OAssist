package ru.outsideproarts.oassist.core.provider.opencode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Расширение usage от OpenCode Zen/Go (Console Inference API): стоимость,
 * источник списания, зачёт в лимит Go и билленые токены. Приходит рядом
 * с usage (OpenAI/Anthropic-протоколы) либо usageMetadata (Gemini) — в том же
 * событии, что несёт финальный usage стрима. Стандартные OpenAI/Anthropic/
 * Google поле не шлют. Формат:
 * https://opencode.ai/v2/docs/console/api/inference#cost
 */
@Serializable
data class OpenCodeUsageExtensionDto(
    @SerialName("billing_source") val billingSource: String? = null,
    /** Стоимость по прайсу модели (включая пиковые часы и длинный контекст), USD. */
    @SerialName("list_cost") val listCost: Double? = null,
    /** true — cost оценён по прайсу, а не списан OpenCode (только BYOK). */
    val estimated: Boolean? = null,
    /** Go: сколько запрос зачтён в лимит плана, USD. */
    val allowance: Double? = null,
    val tokens: OpenCodeBilledTokensDto? = null,
)

/**
 * Билленые токены: `input` — без чтения/записи кэша, `output` — включая
 * `reasoning`. Уточняют нативные поля там, где провайдер их не дал.
 */
@Serializable
data class OpenCodeBilledTokensDto(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    @SerialName("cache_read") val cacheRead: Long? = null,
    @SerialName("cache_write_5m") val cacheWrite5m: Long? = null,
    @SerialName("cache_write_1h") val cacheWrite1h: Long? = null,
)
