package ru.outsideproarts.oassist.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * Источник списания стоимости у OpenCode (`usage.opencode.billing_source`):
 * free — бесплатная модель, balance — предоплаченный баланс Zen, go/go-plus —
 * подписка в пределах лимита, byok — собственный ключ провайдера (стоимость —
 * оценка). Стандартные OpenAI/Anthropic/Gemini источник не сообщают — null.
 */
@Serializable
enum class BillingSource {
    FREE, BALANCE, GO, GO_PLUS, BYOK;

    companion object {
        fun fromWire(value: String?): BillingSource? = when (value?.lowercase()) {
            "free" -> FREE
            "balance" -> BALANCE
            "go" -> GO
            "go-plus", "go_plus" -> GO_PLUS
            "byok" -> BYOK
            else -> null
        }
    }
}

/**
 * Один bucket лимитов провайдера из HTTP-заголовков ответа.
 * [resetAtEpochSeconds] — момент сброса (epoch UTC-секунды), пересчитанный из
 * формата заголовка на момент ответа; null — заголовок не дал распознаваемого
 * времени (или не было заголовка сброса).
 */
@Serializable
data class RateLimitBucket(
    val limit: Long? = null,
    val remaining: Long? = null,
    val resetAtEpochSeconds: Long? = null,
)

/**
 * Срез лимитов провайдера, снятый с HTTP-заголовков ответа
 * (`x-ratelimit-*` OpenAI, `anthropic-ratelimit-*` Anthropic). Это состояние
 * «сколько осталось до ограничения» на момент последнего ответа, а не usage:
 * при склейке статистики выигрывает последний срез ([mergeUsage]).
 */
@Serializable
data class RateLimitSnapshot(
    val requests: RateLimitBucket? = null,
    val tokens: RateLimitBucket? = null,
    val inputTokens: RateLimitBucket? = null,
    val outputTokens: RateLimitBucket? = null,
) {
    val isEmpty: Boolean
        get() = requests == null && tokens == null && inputTokens == null && outputTokens == null
}

/**
 * Каноническая статистика использования одного round-trip'а к модели.
 * Wire-форматы расходятся (OpenAI prompt/completion, Anthropic input/output,
 * Gemini promptTokenCount/candidatesTokenCount) — каждый клиент маппит своё
 * в эту модель через [of]/[mergeUsage].
 *
 * Токены: [inputTokens]/[outputTokens] — как их считает провайдер (у Anthropic
 * input не включает кэш, у OpenAI prompt_tokens включает cached_tokens);
 * [cachedTokens] — чтение кэша, [cacheWriteTokens] — запись в кэш,
 * [reasoningTokens] — «мышление» (входит в output), [toolTokens] — токены
 * результатов инструментов, отданных модели (Gemini toolUsePrompt).
 *
 * Стоимость: [costUsd] — списано с баланса (Zen/прокси; BYOK — оценка, см.
 * [costEstimated]); стандартные API OpenAI/Anthropic/Gemini стоимость не отдают —
 * null значит «неизвестно», а не «бесплатно». [listCostUsd] — по прайсу модели,
 * [allowanceUsd] — сколько запрос зачтён в лимит Go, [billingSource] — источник
 * списания (см. [BillingSource]); всё это — расширение OpenCode.
 *
 * [requests] — число round-trip'ов к модели, вошедших в статистику (у хода —
 * количество тулов агента). [rateLimits] — последний срез лимитов из заголовков.
 */
@Serializable
data class ProviderUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val toolTokens: Long? = null,
    val costUsd: Double? = null,
    val listCostUsd: Double? = null,
    val allowanceUsd: Double? = null,
    val billingSource: BillingSource? = null,
    val costEstimated: Boolean? = null,
    val requests: Int? = null,
    val rateLimits: RateLimitSnapshot? = null,
) {
    val isEmpty: Boolean
        get() = inputTokens == null &&
            outputTokens == null &&
            totalTokens == null &&
            cachedTokens == null &&
            cacheWriteTokens == null &&
            reasoningTokens == null &&
            toolTokens == null &&
            costUsd == null &&
            listCostUsd == null &&
            allowanceUsd == null &&
            billingSource == null &&
            costEstimated == null &&
            requests == null &&
            rateLimits == null

    companion object {
        /**
         * Фабрика с восстановлением [totalTokens]: если провайдер total не прислал,
         * а input+output есть — total = их сумма (так шлют Anthropic и Gemini
         * в message_start/промежуточных чанках). Клиенты, знающие полный состав
         * входа (Anthropic: input + запись/чтение кэша), передают total явно.
         */
        fun of(
            inputTokens: Long? = null,
            outputTokens: Long? = null,
            totalTokens: Long? = null,
            cachedTokens: Long? = null,
            cacheWriteTokens: Long? = null,
            reasoningTokens: Long? = null,
            toolTokens: Long? = null,
            costUsd: Double? = null,
            listCostUsd: Double? = null,
            allowanceUsd: Double? = null,
            billingSource: BillingSource? = null,
            costEstimated: Boolean? = null,
            requests: Int? = null,
            rateLimits: RateLimitSnapshot? = null,
        ): ProviderUsage {
            val total = totalTokens
                ?: if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
            return ProviderUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = total,
                cachedTokens = cachedTokens,
                cacheWriteTokens = cacheWriteTokens,
                reasoningTokens = reasoningTokens,
                toolTokens = toolTokens,
                costUsd = costUsd,
                listCostUsd = listCostUsd,
                allowanceUsd = allowanceUsd,
                billingSource = billingSource,
                costEstimated = costEstimated,
                requests = requests,
                rateLimits = rateLimits,
            )
        }
    }
}

/**
 * Сумма статистики двух round-trip'ов одного хода: токены и cost складываются
 * (cost — только известные значения: если у одного раунда провайдер не прислал
 * cost, итог может быть занижен — честнее, чем молча выдумывать). Статус
 * источника/оценки берётся из последнего известного, срез лимитов — тоже
 * последний (это состояние на момент ответа, а не накопительная величина).
 */
fun mergeUsage(a: ProviderUsage?, b: ProviderUsage?): ProviderUsage? {
    if (a == null) return b?.takeUnless { it.isEmpty }
    if (b == null) return a.takeUnless { it.isEmpty }
    return ProviderUsage.of(
        inputTokens = sumNullable(a.inputTokens, b.inputTokens),
        outputTokens = sumNullable(a.outputTokens, b.outputTokens),
        totalTokens = sumNullable(a.totalTokens, b.totalTokens),
        cachedTokens = sumNullable(a.cachedTokens, b.cachedTokens),
        cacheWriteTokens = sumNullable(a.cacheWriteTokens, b.cacheWriteTokens),
        reasoningTokens = sumNullable(a.reasoningTokens, b.reasoningTokens),
        toolTokens = sumNullable(a.toolTokens, b.toolTokens),
        costUsd = sumNullable(a.costUsd, b.costUsd),
        listCostUsd = sumNullable(a.listCostUsd, b.listCostUsd),
        allowanceUsd = sumNullable(a.allowanceUsd, b.allowanceUsd),
        billingSource = b.billingSource ?: a.billingSource,
        costEstimated = b.costEstimated ?: a.costEstimated,
        requests = sumNullable(a.requests, b.requests),
        rateLimits = b.rateLimits ?: a.rateLimits,
    ).takeUnless { it.isEmpty }
}

private fun sumNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}

private fun sumNullable(a: Int?, b: Int?): Int? = when {
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
 * склейка через [mergeUsage] — токены/cost складываются, лимиты — последние.
 * Пусто/нет usage — null.
 */
fun aggregateUsage(turns: List<StoredTurn>): ProviderUsage? =
    turns.mapNotNull { it.usage }.fold(null, ::mergeUsage)

/** Срез агрегатной статистики за жизнь процесса (in-memory, без персиста). */
data class UsageTotalsState(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val toolTokens: Long = 0,
    /** Сумма известных cost; null — ни один ход не прислал стоимость. */
    val costUsd: Double? = null,
    val listCostUsd: Double? = null,
    val allowanceUsd: Double? = null,
    val turns: Int = 0,
    /** Сумма round-trip'ов к модели (ход без учёта — 1 запрос). */
    val requests: Int = 0,
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
            UsageTotalsState(
                inputTokens = s.inputTokens + (usage.inputTokens ?: 0),
                outputTokens = s.outputTokens + (usage.outputTokens ?: 0),
                totalTokens = s.totalTokens + (usage.totalTokens ?: 0),
                cachedTokens = s.cachedTokens + (usage.cachedTokens ?: 0),
                cacheWriteTokens = s.cacheWriteTokens + (usage.cacheWriteTokens ?: 0),
                reasoningTokens = s.reasoningTokens + (usage.reasoningTokens ?: 0),
                toolTokens = s.toolTokens + (usage.toolTokens ?: 0),
                costUsd = s.costUsd sumKnown usage.costUsd,
                listCostUsd = s.listCostUsd sumKnown usage.listCostUsd,
                allowanceUsd = s.allowanceUsd sumKnown usage.allowanceUsd,
                turns = s.turns + 1,
                requests = s.requests + (usage.requests ?: 1),
            )
        }
    }

    /** Конец сессии (закрытие оверлея/чата/шаринга) — обнулить итоги. */
    fun reset() {
        _state.value = UsageTotalsState()
    }
}

/** null — ни один ход не прислал значение; иначе сумма известных. */
private infix fun Double?.sumKnown(other: Double?): Double? = when {
    this == null && other == null -> null
    else -> (this ?: 0.0) + (other ?: 0.0)
}
