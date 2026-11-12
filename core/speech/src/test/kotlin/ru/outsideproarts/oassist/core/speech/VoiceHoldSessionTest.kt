package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHoldSessionTest {
    @Test
    fun finalBeforeReleaseIsBufferedAndDeliveredOnce() {
        val hold = VoiceHoldSession(CaptureMode.STT)
        assertNull(hold.onFinal("question"))
        assertFalse(hold.captureActive)
        assertEquals("question", hold.release())
        assertNull(hold.release())
        assertNull(hold.onFinal("duplicate"))
    }

    @Test
    fun releaseBeforeFinalAllowsTheLaterTranscript() {
        val hold = VoiceHoldSession(CaptureMode.STT)
        assertNull(hold.release())
        assertTrue(hold.captureActive)
        assertEquals("question", hold.onFinal("question"))
        assertNull(hold.onFinal("duplicate"))
    }

    @Test
    fun cancellationDiscardsBufferedFinal() {
        val hold = VoiceHoldSession(CaptureMode.STT)
        hold.onFinal("uncommitted speech")
        assertFalse(hold.cancel())
        assertNull(hold.release())
        assertNull(hold.onFinal("late result"))
    }

    @Test
    fun cancellationStopsPendingCaptureAndIgnoresLateFinal() {
        val hold = VoiceHoldSession(CaptureMode.STT)
        assertTrue(hold.cancel())
        assertFalse(hold.cancel())
        assertNull(hold.onFinal("late result"))
        assertNull(hold.release())
    }

    @Test
    fun errorEndsCaptureWithoutCommittingText() {
        val hold = VoiceHoldSession(CaptureMode.STT)
        hold.onError()
        assertFalse(hold.captureActive)
        assertNull(hold.release())
        assertNull(hold.onFinal("late result"))
    }
}
