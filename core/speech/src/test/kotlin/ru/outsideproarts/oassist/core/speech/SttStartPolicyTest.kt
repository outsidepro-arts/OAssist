package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttStartPolicyTest {

    @Test
    fun busyBeforePartial_retriesWithBackoff() {
        val first = SttStartPolicy.decide(1, SttFailure.AsyncError(SttError.BUSY, hadPartial = false))
        val second = SttStartPolicy.decide(2, SttFailure.AsyncError(SttError.BUSY, hadPartial = false))
        val third = SttStartPolicy.decide(3, SttFailure.AsyncError(SttError.BUSY, hadPartial = false))

        assertEquals(SttStartDecision.Retry(300L), first)
        assertEquals(SttStartDecision.Retry(600L), second)
        assertEquals(SttStartDecision.Retry(1200L), third)
    }

    @Test
    fun busyAfterPartial_fails() {
        val decision = SttStartPolicy.decide(1, SttFailure.AsyncError(SttError.BUSY, hadPartial = true))

        assertTrue(decision is SttStartDecision.Fail)
    }

    @Test
    fun budgetExhausted_fails() {
        val decision = SttStartPolicy.decide(
            SttStartPolicy.MAX_ATTEMPTS,
            SttFailure.AsyncError(SttError.BUSY, hadPartial = false),
        )

        assertTrue(decision is SttStartDecision.Fail)
    }

    @Test
    fun syncStartFailed_retryable() {
        assertEquals(
            SttStartDecision.Retry(300L),
            SttStartPolicy.decide(1, SttFailure.SyncStartFailed(CaptureError.INIT_FAILED)),
        )
        assertEquals(
            SttStartDecision.Retry(300L),
            SttStartPolicy.decide(1, SttFailure.SyncStartFailed(CaptureError.BUSY)),
        )
    }

    @Test
    fun realErrors_failImmediately() {
        assertTrue(
            SttStartPolicy.decide(1, SttFailure.SyncStartFailed(CaptureError.NO_PERMISSION))
                is SttStartDecision.Fail,
        )
        assertTrue(
            SttStartPolicy.decide(1, SttFailure.SyncStartFailed(CaptureError.NO_RECOGNIZER))
                is SttStartDecision.Fail,
        )
        assertTrue(
            SttStartPolicy.decide(1, SttFailure.AsyncError(SttError.NO_SPEECH, hadPartial = false))
                is SttStartDecision.Fail,
        )
        assertTrue(
            SttStartPolicy.decide(1, SttFailure.AsyncError(SttError.NETWORK, hadPartial = false))
                is SttStartDecision.Fail,
        )
        assertTrue(
            SttStartPolicy.decide(1, SttFailure.AsyncError(SttError.UNKNOWN, hadPartial = false))
                is SttStartDecision.Fail,
        )
    }
}
