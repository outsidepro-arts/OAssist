package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.UsageTotalsState
import ru.outsideproarts.oassist.core.ui.R
import java.text.NumberFormat
import java.util.Locale

/**
 * Одна строка статистики хода: входные/выходные токены раздельно
 * («сгенерированные» — output, их отсутствие — главная жалоба), кэш и
 * (если провайдер прислал) стоимость. Если провайдер дал только total —
 * фолбэк на «Токены: N». Статичный текст после Finish — НЕ live region.
 * Пустой usage — ничего не рендерим.
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
        } else {
            usage.totalTokens?.let { add(stringResource(R.string.usage_tokens, formatTokenCount(it))) }
        }
        usage.cachedTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_line_cache, formatTokenCount(it))) }
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
 * стоимость и число ходов. Пустые итоги — ничего не рендерим.
 */
@Composable
fun OAssistUsageTotalsLine(
    totals: UsageTotalsState,
    modifier: Modifier = Modifier,
) {
    if (totals.isEmpty) return
    val cost = totals.costUsd
    val text = if (cost != null) {
        stringResource(
            R.string.usage_totals_cost,
            formatTokenCount(totals.totalTokens),
            formatCostAmount(cost),
            totals.turns,
        )
    } else {
        stringResource(R.string.usage_totals, formatTokenCount(totals.totalTokens), totals.turns)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Детальная статистика диалога (окно «Информация о чате»): входные/выходные
 * токены раздельно, кэш, итог, стоимость, число ходов. Поля без данных
 * не показываются (cost/cached у большинства провайдеров отсутствуют).
 * Статичный текст — не live region.
 */
@Composable
fun OAssistUsageDetails(
    usage: ProviderUsage?,
    turns: Int,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        usage?.inputTokens?.let { add(stringResource(R.string.usage_input, formatTokenCount(it))) }
        usage?.outputTokens?.let { add(stringResource(R.string.usage_output, formatTokenCount(it))) }
        usage?.cachedTokens?.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.usage_cached, formatTokenCount(it))) }
        usage?.totalTokens?.let { add(stringResource(R.string.usage_tokens, formatTokenCount(it))) }
        usage?.costUsd?.let { add(stringResource(R.string.usage_cost, formatCostAmount(it))) }
        if (turns > 0) add(stringResource(R.string.usage_turns, turns))
    }
    if (rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
