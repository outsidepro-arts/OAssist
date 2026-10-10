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
}
