package ru.outsideproarts.oassist.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * Каноническая статистика использования одного round-trip'а к модели.
 * Wire-форматы расходятся (OpenAI prompt/completion, Anthropic input/output,
 * Gemini promptTokenCount/candidatesTokenCount) — каждый клиент маппит своё
 * в эту модель через [of]/[mergeUsage].
 *
 * [costUsd] заполняется только если провайдер прислал стоимость (Zen/прокси);
 * стандартные API OpenAI/Anthropic/Gemini её не отдают — null значит «неизвестно»,
 * а не «бесплатно».
 */
@Serializable
data class ProviderUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val costUsd: Double? = null,
) {
    val isEmpty: Boolean
        get() = inputTokens == null &&
            outputTokens == null &&
            totalTokens == null &&
            cachedTokens == null &&
            costUsd == null

    companion object {
        /**
         * Фабрика с восстановлением [totalTokens]: если провайдер total не прислал,
         * а input+output есть — total = их сумма (так шлют Anthropic и Gemini
         * в message_start/промежуточных чанках).
         */
        fun of(
            inputTokens: Long? = null,
            outputTokens: Long? = null,
            totalTokens: Long? = null,
            cachedTokens: Long? = null,
            costUsd: Double? = null,
        ): ProviderUsage {
            val total = totalTokens
                ?: if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
            return ProviderUsage(inputTokens, outputTokens, total, cachedTokens, costUsd)
        }
    }
}

/**
 * Сумма статистики двух round-trip'ов одного хода: токены складываются,
 * cost — только известные значения (если у одного раунда провайдер не прислал
 * cost, итог может быть занижен — честнее, чем молча выдумывать).
 */
fun mergeUsage(a: ProviderUsage?, b: ProviderUsage?): ProviderUsage? {
    if (a == null) return b?.takeUnless { it.isEmpty }
    if (b == null) return a.takeUnless { it.isEmpty }
    return ProviderUsage.of(
        inputTokens = sumNullable(a.inputTokens, b.inputTokens),
        outputTokens = sumNullable(a.outputTokens, b.outputTokens),
        totalTokens = sumNullable(a.totalTokens, b.totalTokens),
        cachedTokens = sumNullable(a.cachedTokens, b.cachedTokens),
        costUsd = sumNullable(a.costUsd, b.costUsd),
    ).takeUnless { it.isEmpty }
}

private fun sumNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}

private fun sumNullable(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}

/**
 * Сумма статистики всех ходов диалога (для окна «Информация о чате»):
 * склейка через [mergeUsage] — input/output/cached складываются, cost —
 * только известные. Пусто/нет usage — null.
 */
fun aggregateUsage(turns: List<StoredTurn>): ProviderUsage? =
    turns.mapNotNull { it.usage }.fold(null, ::mergeUsage)

/** Срез агрегатной статистики за жизнь процесса (in-memory, без персиста). */
data class UsageTotalsState(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** Сумма известных cost; null — ни один ход не прислал стоимость. */
    val costUsd: Double? = null,
    val turns: Int = 0,
) {
    val isEmpty: Boolean get() = turns == 0
}

/**
 * Процесс-скоупные итоги использования: сумма по завершённым ходам
 * (см. `AgentEvents.onTurnUsage`). Живут в рамках активной сессии:
 * хосты (оверлей/чат/шаринг) вызывают [reset] при закрытии — «Всего»
 * не переживает сессию. Персист осознанно не делаем (решение v1).
 */
class UsageTotals {
    private val _state = MutableStateFlow(UsageTotalsState())
    val state: StateFlow<UsageTotalsState> = _state.asStateFlow()

    fun record(usage: ProviderUsage) {
        _state.update { s ->
            val cost = when {
                s.costUsd == null && usage.costUsd == null -> null
                else -> (s.costUsd ?: 0.0) + (usage.costUsd ?: 0.0)
            }
            UsageTotalsState(
                inputTokens = s.inputTokens + (usage.inputTokens ?: 0),
                outputTokens = s.outputTokens + (usage.outputTokens ?: 0),
                totalTokens = s.totalTokens + (usage.totalTokens ?: 0),
                cachedTokens = s.cachedTokens + (usage.cachedTokens ?: 0),
                costUsd = cost,
                turns = s.turns + 1,
            )
        }
    }

    /** Конец сессии (закрытие оверлея/чата/шаринга) — обнулить итоги. */
    fun reset() {
        _state.value = UsageTotalsState()
    }
}
