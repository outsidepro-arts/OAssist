package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {

    @Test
    fun of_recoversTotalFromInputAndOutput() {
        val usage = ProviderUsage.of(inputTokens = 100, outputTokens = 50)
        assertEquals(150L, usage.totalTokens)
    }

    @Test
    fun of_keepsProvidedTotal() {
        val usage = ProviderUsage.of(inputTokens = 100, outputTokens = 50, totalTokens = 170)
        assertEquals(170L, usage.totalTokens)
    }

    @Test
    fun of_totalNullWhenPartial() {
        val usage = ProviderUsage.of(inputTokens = 100)
        assertNull(usage.totalTokens)
    }

    @Test
    fun merge_sumsTokensAndKnownCost() {
        val a = ProviderUsage.of(inputTokens = 100, outputTokens = 20, costUsd = 0.001)
        val b = ProviderUsage.of(inputTokens = 300, outputTokens = 40)
        val merged = mergeUsage(a, b)!!
        assertEquals(400L, merged.inputTokens)
        assertEquals(60L, merged.outputTokens)
        assertEquals(460L, merged.totalTokens)
        assertEquals(0.001, merged.costUsd!!, 1e-9)
    }

    @Test
    fun merge_sumsBothCosts() {
        val a = ProviderUsage.of(totalTokens = 10, costUsd = 0.002)
        val b = ProviderUsage.of(totalTokens = 20, costUsd = 0.003)
        assertEquals(0.005, mergeUsage(a, b)!!.costUsd!!, 1e-9)
    }

    @Test
    fun merge_nullPassesThrough() {
        val a = ProviderUsage.of(inputTokens = 5, outputTokens = 5)
        assertEquals(a, mergeUsage(a, null))
        assertEquals(a, mergeUsage(null, a))
        assertNull(mergeUsage(null, null))
    }

    @Test
    fun merge_emptyBecomesNull() {
        assertNull(mergeUsage(ProviderUsage(), ProviderUsage()))
    }

    @Test
    fun totals_accumulatesTurns() {
        val totals = UsageTotals()
        totals.record(ProviderUsage.of(inputTokens = 100, outputTokens = 50, costUsd = 0.01))
        totals.record(ProviderUsage.of(inputTokens = 200, outputTokens = 25))
        val state = totals.state.value
        assertEquals(300L, state.inputTokens)
        assertEquals(75L, state.outputTokens)
        assertEquals(375L, state.totalTokens)
        assertEquals(0.01, state.costUsd!!, 1e-9)
        assertEquals(2, state.turns)
        assertTrue(!state.isEmpty)
    }

    @Test
    fun totals_costNullUntilProviderSendsIt() {
        val totals = UsageTotals()
        totals.record(ProviderUsage.of(totalTokens = 10))
        assertNull(totals.state.value.costUsd)
        totals.record(ProviderUsage.of(totalTokens = 10, costUsd = 0.5))
        assertEquals(0.5, totals.state.value.costUsd!!, 1e-9)
    }

    @Test
    fun totals_reset_clearsEverything() {
        val totals = UsageTotals()
        totals.record(ProviderUsage.of(inputTokens = 1, outputTokens = 2, costUsd = 0.1))
        totals.reset()
        val state = totals.state.value
        assertEquals(0L, state.totalTokens)
        assertNull(state.costUsd)
        assertEquals(0, state.turns)
        assertTrue(state.isEmpty)
    }

    @Test
    fun aggregate_sumsTurnsAndSkipsMissingUsage() {
        val turns = listOf(
            StoredTurn(userText = "1", usage = ProviderUsage.of(inputTokens = 10, outputTokens = 5, costUsd = 0.2)),
            StoredTurn(userText = "2"),
            StoredTurn(userText = "3", usage = ProviderUsage.of(inputTokens = 1, outputTokens = 2)),
        )
        val total = aggregateUsage(turns)!!
        assertEquals(11L, total.inputTokens)
        assertEquals(7L, total.outputTokens)
        assertEquals(18L, total.totalTokens)
        // Cost только у известного хода — не размазываем и не выдумываем.
        assertEquals(0.2, total.costUsd!!, 1e-9)
    }

    @Test
    fun aggregate_noUsageOrNull_isNull() {
        assertNull(aggregateUsage(emptyList()))
        assertNull(aggregateUsage(listOf(StoredTurn(userText = "x"))))
    }

    @Test
    fun of_mapsExtendedFields() {
        val limits = RateLimitSnapshot(requests = RateLimitBucket(limit = 100, remaining = 40))
        val usage = ProviderUsage.of(
            inputTokens = 10,
            outputTokens = 5,
            cachedTokens = 3,
            cacheWriteTokens = 2,
            reasoningTokens = 4,
            toolTokens = 7,
            costUsd = 0.01,
            listCostUsd = 0.02,
            allowanceUsd = 0.005,
            billingSource = BillingSource.GO,
            costEstimated = true,
            requests = 2,
            rateLimits = limits,
        )
        assertEquals(2L, usage.cacheWriteTokens)
        assertEquals(4L, usage.reasoningTokens)
        assertEquals(7L, usage.toolTokens)
        assertEquals(0.02, usage.listCostUsd!!, 1e-9)
        assertEquals(0.005, usage.allowanceUsd!!, 1e-9)
        assertEquals(BillingSource.GO, usage.billingSource)
        assertEquals(true, usage.costEstimated)
        assertEquals(2, usage.requests)
        assertEquals(limits, usage.rateLimits)
    }

    @Test
    fun merge_sumsExtendedTokensAndRequests() {
        val a = ProviderUsage.of(
            cacheWriteTokens = 2,
            reasoningTokens = 4,
            toolTokens = 1,
            listCostUsd = 0.02,
            allowanceUsd = 0.005,
            requests = 1,
        )
        val b = ProviderUsage.of(
            cacheWriteTokens = 3,
            reasoningTokens = 6,
            toolTokens = 2,
            listCostUsd = 0.03,
            allowanceUsd = 0.007,
            requests = 2,
        )
        val merged = mergeUsage(a, b)!!
        assertEquals(5L, merged.cacheWriteTokens)
        assertEquals(10L, merged.reasoningTokens)
        assertEquals(3L, merged.toolTokens)
        assertEquals(0.05, merged.listCostUsd!!, 1e-9)
        assertEquals(0.012, merged.allowanceUsd!!, 1e-9)
        assertEquals(3, merged.requests)
    }

    @Test
    fun merge_keepsLatestRateLimitsAndBillingSource() {
        val old = ProviderUsage.of(
            billingSource = BillingSource.BALANCE,
            rateLimits = RateLimitSnapshot(requests = RateLimitBucket(remaining = 10)),
        )
        val new = ProviderUsage.of(
            billingSource = BillingSource.GO,
            rateLimits = RateLimitSnapshot(requests = RateLimitBucket(remaining = 3)),
        )
        val merged = mergeUsage(old, new)!!
        assertEquals(BillingSource.GO, merged.billingSource)
        assertEquals(3L, merged.rateLimits!!.requests!!.remaining)
        // Лимиты — состояние «на сейчас»: при склейке выигрывает последний.
        assertEquals(10L, mergeUsage(new, old)!!.rateLimits!!.requests!!.remaining)
    }

    @Test
    fun rateLimitsOnly_isNotEmpty() {
        // Usage без токенов, но с лимитами — не пустой: остатки должны дойти до UI.
        val usage = ProviderUsage.of(rateLimits = RateLimitSnapshot(tokens = RateLimitBucket(remaining = 5)))
        assertEquals(false, usage.isEmpty)
        assertEquals(usage, mergeUsage(null, usage))
    }

    @Test
    fun totals_accumulatesExtendedAndCountsRequests() {
        val totals = UsageTotals()
        totals.record(ProviderUsage.of(inputTokens = 1, outputTokens = 1, cacheWriteTokens = 2, reasoningTokens = 3))
        totals.record(ProviderUsage.of(inputTokens = 1, outputTokens = 1, toolTokens = 4, requests = 5))
        val state = totals.state.value
        assertEquals(2L, state.cacheWriteTokens)
        assertEquals(3L, state.reasoningTokens)
        assertEquals(4L, state.toolTokens)
        // Ход без явных запросов считается одним запросом к модели.
        assertEquals(6, state.requests)
    }

    @Test
    fun aggregate_keepsLatestRateLimits() {
        val turns = listOf(
            StoredTurn(userText = "1", usage = ProviderUsage.of(
                inputTokens = 1,
                outputTokens = 1,
                rateLimits = RateLimitSnapshot(requests = RateLimitBucket(remaining = 9)),
            )),
            StoredTurn(userText = "2", usage = ProviderUsage.of(
                inputTokens = 1,
                outputTokens = 1,
                requests = 3,
                rateLimits = RateLimitSnapshot(requests = RateLimitBucket(remaining = 4)),
            )),
        )
        val total = aggregateUsage(turns)!!
        assertEquals(4L, total.rateLimits!!.requests!!.remaining)
        assertEquals(3, total.requests)
    }
}
