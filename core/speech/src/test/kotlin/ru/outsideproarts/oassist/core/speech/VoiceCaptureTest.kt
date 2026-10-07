package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavEncoderTest {

    @Test
    fun emptyPcm_headerOnly44() {
        val wav = WavEncoder.pcmToWav(ShortArray(0))

        assertEquals(44, wav.size)
        assertEquals("RIFF", wav.sliceArray(0..3).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.sliceArray(8..11).toString(Charsets.US_ASCII))
        assertEquals("fmt ", wav.sliceArray(12..15).toString(Charsets.US_ASCII))
        assertEquals("data", wav.sliceArray(36..39).toString(Charsets.US_ASCII))
    }

    @Test
    fun oneSecondPcm_sizesMatch() {
        val wav = WavEncoder.pcmToWav(ShortArray(WavEncoder.SAMPLE_RATE))

        assertEquals(44 + 16000 * 2, wav.size)
        assertEquals(1, WavEncoder.durationSec(WavEncoder.SAMPLE_RATE))
    }

    @Test
    fun durationSec_truncatesAndZeroOnEmpty() {
        assertEquals(0, WavEncoder.durationSec(0))
        assertEquals(0, WavEncoder.durationSec(15999))
        assertEquals(2, WavEncoder.durationSec(32000))
    }
}

class VoiceCaptureSessionTest {

    @Test
    fun startStop_cycle() {
        val session = VoiceCaptureSession(clock = { 1000L })

        assertNull(session.start(CaptureMode.STT))
        assertEquals(CaptureState.Listening(CaptureMode.STT, 1000L), session.state)
        assertEquals(CaptureMode.STT, session.stop())
        assertEquals(CaptureState.Idle, session.state)
    }

    @Test
    fun doubleStart_busy_keepsFirst() {
        val session = VoiceCaptureSession(clock = { 7L })

        assertNull(session.start(CaptureMode.RAW))
        assertEquals(CaptureError.BUSY, session.start(CaptureMode.STT))
        assertEquals(CaptureMode.RAW, (session.state as CaptureState.Listening).mode)
    }

    @Test
    fun stopWhenIdle_null() {
        val session = VoiceCaptureSession()

        assertNull(session.stop())
        assertTrue(session.state is CaptureState.Idle)
    }

    @Test
    fun reset_clearsListening() {
        val session = VoiceCaptureSession()

        session.start(CaptureMode.STT)
        session.reset()

        assertTrue(session.state is CaptureState.Idle)
        assertNull(session.stop())
    }
}

class SttCallbackGateTest {

    @Test
    fun newSession_invalidatesPreviousToken() {
        val gate = SttCallbackGate()
        val first = gate.newSession()
        assertTrue(gate.isActive(first))

        val second = gate.newSession()

        assertFalse(gate.isActive(first))
        assertTrue(gate.isActive(second))
    }

    @Test
    fun invalidate_dropsAllTokens() {
        val gate = SttCallbackGate()
        val token = gate.newSession()

        gate.invalidate()

        assertFalse(gate.isActive(token))
    }
}
