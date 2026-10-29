package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.model.BillingSource
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.RateLimitBucket
import ru.outsideproarts.oassist.core.model.UsageTotalsState
import ru.outsideproarts.oassist.core.ui.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Одна строка статистики хода: входные/выходные токены раздельно
 * («сгенерированные» — output, их отсутствие — главная жалоба), мышление,
 * кэш (чтение/запись) и (если провайдер прислал) стоимость. Если провайдер
 * дал только total — фолбэк на «Токены: N». Статичный текст после Finish —
 * НЕ live region. Пустой usage — ничего не рендерим.
 */
@Composable
fun OAssistUsageLine(
    usage: ProviderUsage?,
    modifier: Modifier = Modifier,
) {
    if (usage == null || usage.isEmpty) return
    val input = usage.inputTokens
    val output = usage.outputTokens
    val parts = buildList {
        if (input != null || output != null) {
            input?.let { add(stringResource(R.string.usage_line_in, formatTokenCount(it))) }
            output?.let { add(stringResource(R.string.usage_line_out, formatTokenCount(it))) }
            usage.reasoningTokens?.takeIf { it > 0 }
                ?.let { add(stringResource(R.string.usage_reasoning, formatTokenCount(it))) }
        } else {
            usage.totalTokens?.let { add(stringResource(R.string.usage_tokens, formatTokenCount(it))) }
        }
        usage.cachedTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_line_cache, formatTokenCount(it))) }
        usage.cacheWriteTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_cache_write, formatTokenCount(it))) }
        usage.costUsd?.let { add(stringResource(R.string.usage_cost, formatCostAmount(it))) }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Агрегат за жизнь процесса (in-memory): суммарные токены, известная
 * стоимость, число ходов и запросов к модели. Пустые итоги — ничего не рендерим.
 */
@Composable
fun OAssistUsageTotalsLine(
    totals: UsageTotalsState,
    modifier: Modifier = Modifier,
) {
    if (totals.isEmpty) return
    val cost = totals.costUsd
    val base = if (cost != null) {
        stringResource(
            R.string.usage_totals_cost,
            formatTokenCount(totals.totalTokens),
            formatCostAmount(cost),
            totals.turns,
        )
    } else {
        stringResource(R.string.usage_totals, formatTokenCount(totals.totalTokens), totals.turns)
    }
    val text = if (totals.requests > 0) {
        base + " · " + stringResource(R.string.usage_totals_requests, totals.requests)
    } else {
        base
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Детальная статистика диалога (окно «Информация о чате»): токены с разбивкой
 * (кэш-чтение/запись, мышление, токены инструментов), запросы к модели,
 * блок стоимости (списано/по прайсу/зачтено в лимит/источник) и блок лимитов
 * из заголовков ответа провайдера. Поля без данных не показываются.
 * Статичный текст — не live region.
 */
@Composable
fun OAssistUsageDetails(
    usage: ProviderUsage?,
    turns: Int,
    modifier: Modifier = Modifier,
) {
    val tokenRows = buildList {
        usage?.inputTokens?.let { add(stringResource(R.string.usage_input, formatTokenCount(it))) }
        usage?.cachedTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_cached, formatTokenCount(it))) }
        usage?.outputTokens?.let { add(stringResource(R.string.usage_output, formatTokenCount(it))) }
        usage?.reasoningTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_reasoning, formatTokenCount(it))) }
        usage?.cacheWriteTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_cache_write, formatTokenCount(it))) }
        usage?.toolTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_tool, formatTokenCount(it))) }
        usage?.totalTokens?.let { add(stringResource(R.string.usage_tokens, formatTokenCount(it))) }
        usage?.requests?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_requests, it)) }
        if (turns > 0) add(stringResource(R.string.usage_turns, turns))
    }
    val costRows = usageCostRows(usage)
    val limitRows = usageLimitRows(usage)
    if (tokenRows.isEmpty() && costRows.isEmpty() && limitRows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        tokenRows.forEach { UsageDetailRow(it) }
        if (costRows.isNotEmpty()) {
            OAssistSectionHeading(text = stringResource(R.string.usage_cost_title))
            costRows.forEach { UsageDetailRow(it) }
        }
        if (limitRows.isNotEmpty()) {
            OAssistSectionHeading(text = stringResource(R.string.usage_limits_title))
            limitRows.forEach { UsageDetailRow(it) }
        }
    }
}

@Composable
private fun UsageDetailRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Строка стоимости: списано (с пометкой оценки), прайс, зачёт в лимит, источник. */
@Composable
private fun usageCostRows(usage: ProviderUsage?): List<String> = buildList {
    val charged = usage?.costUsd
    val estimated = usage?.costEstimated
    if (charged != null) {
        add(
            if (estimated == true) {
                stringResource(R.string.usage_cost_charged_estimated, formatCostAmount(charged))
            } else {
                stringResource(R.string.usage_cost_charged, formatCostAmount(charged))
            },
        )
    }
    usage?.listCostUsd?.let { add(stringResource(R.string.usage_cost_list, formatCostAmount(it))) }
    usage?.allowanceUsd?.let { add(stringResource(R.string.usage_cost_allowance, formatCostAmount(it))) }
    usage?.billingSource?.let { source ->
        val label = when (source) {
            BillingSource.FREE -> R.string.usage_billing_free
            BillingSource.BALANCE -> R.string.usage_billing_balance
            BillingSource.GO -> R.string.usage_billing_go
            BillingSource.GO_PLUS -> R.string.usage_billing_go_plus
            BillingSource.BYOK -> R.string.usage_billing_byok
        }
        add(stringResource(label))
    }
}

/** Строки лимитов: по одному bucket'у на семейство (запросы/токены/вход/выход). */
@Composable
private fun usageLimitRows(usage: ProviderUsage?): List<String> {
    val snapshot = usage?.rateLimits ?: return emptyList()
    if (snapshot.isEmpty) return emptyList()
    return listOfNotNull(
        snapshot.requests.limitRow(R.string.usage_limit_requests),
        snapshot.tokens.limitRow(R.string.usage_limit_tokens),
        snapshot.inputTokens.limitRow(R.string.usage_limit_input_tokens),
        snapshot.outputTokens.limitRow(R.string.usage_limit_output_tokens),
    )
}

@Composable
private fun RateLimitBucket?.limitRow(labelRes: Int): String? {
    val bucket = this ?: return null
    val remaining = bucket.remaining
    val limit = bucket.limit
    val detail = buildList {
        when {
            remaining != null && limit != null -> add(
                stringResource(
                    R.string.usage_limit_remaining_of,
                    formatTokenCount(remaining),
                    formatTokenCount(limit),
                ),
            )
            remaining != null ->
                add(stringResource(R.string.usage_limit_remaining, formatTokenCount(remaining)))
            limit != null ->
                add(stringResource(R.string.usage_limit_total, formatTokenCount(limit)))
        }
        bucket.resetAtEpochSeconds?.let {
            add(stringResource(R.string.usage_limit_reset, formatResetTime(it)))
        }
    }
    if (detail.isEmpty()) return null
    return stringResource(R.string.usage_limit_row, stringResource(labelRes), detail.joinToString(" · "))
}

internal fun formatTokenCount(count: Long): String =
    NumberFormat.getIntegerInstance().format(count)

/** `$0.005` для мелких сумм, `$0.12`/`$1.00` — до двух знаков; хвостовые нули срезаем. */
internal fun formatCostAmount(costUsd: Double): String {
    val pattern = if (costUsd > 0 && costUsd < 0.01) "%.4f" else "%.2f"
    return String.format(Locale.US, pattern, costUsd)
        .trimEnd('0')
        .trimEnd('.')
        .ifEmpty { "0" }
}

/** Момент сброса лимита: локальное дата+время («29.10 14:32»). */
internal fun formatResetTime(epochSeconds: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
