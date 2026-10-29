package ru.outsideproarts.oassist.core.provider

import ru.outsideproarts.oassist.core.model.BillingSource
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.provider.opencode.OpenCodeUsageExtensionDto

/**
 * Сборка канонической [ProviderUsage] из wire-полей протокола и (опционально)
 * расширения OpenCode. Нативные поля провайдера — источник истины; пробелы
 * добираем из билленых токенов OpenCode (`input` там без кэша — смешивать
 * семантики нельзя, поэтому только заполняем пропуски). Cost берётся из
 * переданного значения (top-level либо `usage.cost`), детали стоимости —
 * из расширения. Пустой результат — null.
 */
internal fun mapProviderUsage(
    inputTokens: Long? = null,
    outputTokens: Long? = null,
    totalTokens: Long? = null,
    cachedTokens: Long? = null,
    cacheWriteTokens: Long? = null,
    reasoningTokens: Long? = null,
    toolTokens: Long? = null,
    costUsd: Double? = null,
    openCode: OpenCodeUsageExtensionDto? = null,
): ProviderUsage? {
    val billed = openCode?.tokens
    val input = inputTokens ?: billed?.input
    val output = outputTokens ?: billed?.output
    val cacheWrite = cacheWriteTokens
        ?: sumNullable(billed?.cacheWrite5m, billed?.cacheWrite1h)
    val mapped = ProviderUsage.of(
        inputTokens = input,
        outputTokens = output,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens ?: billed?.cacheRead,
        cacheWriteTokens = cacheWrite,
        reasoningTokens = reasoningTokens ?: billed?.reasoning,
        toolTokens = toolTokens,
        costUsd = costUsd,
        listCostUsd = openCode?.listCost,
        allowanceUsd = openCode?.allowance,
        billingSource = BillingSource.fromWire(openCode?.billingSource),
        costEstimated = openCode?.estimated,
    )
    return mapped.takeUnless { it.isEmpty }
}

private fun sumNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}
