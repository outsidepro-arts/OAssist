package ru.outsideproarts.oassist.core.ui.components

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
 * Одна строка статистики хода: токены + (если провайдер прислал) стоимость.
 * Статичный текст после Finish — НЕ live region. Пустой usage — ничего не рендерим.
 */
@Composable
fun OAssistUsageLine(
    usage: ProviderUsage?,
    modifier: Modifier = Modifier,
) {
    if (usage == null || usage.isEmpty) return
    val input = usage.inputTokens
    val output = usage.outputTokens
    val tokens = usage.totalTokens
        ?: if (input != null && output != null) input + output else null
    val parts = buildList {
        tokens?.let { add(stringResource(R.string.usage_tokens, formatTokenCount(it))) }
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
