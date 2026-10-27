package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentVoiceCaptureTest {
    private class FakeBackend : AgentSttBackend {
        var owner: SttListener? = null
        var onStart: ((SttListener) -> Unit)? = null
        var onStop: ((SttListener) -> Unit)? = null
        var startError: CaptureError? = null
        var starts = 0
        var stops = 0
        var cancels = 0

        override fun start(language: String, listener: SttListener): CaptureError? {
            starts++
            if (owner != null) return CaptureError.BUSY
            startError?.let { return it }
            owner = listener
            onStart?.invoke(listener)
            return null
        }

        override fun stop(listener: SttListener): Boolean {
            if (owner !== listener) return false
            stops++
            onStop?.invoke(listener)
            return true
        }

        override fun cancel(listener: SttListener): Boolean {
            if (owner !== listener) return false
            cancels++
            owner = null
            listener.onSttCancelled()
            return true
        }

        fun final(text: String) {
            val current = owner ?: error("no active owner")
            owner = null
            current.onFinal(text)
        }

        fun error(error: SttError) {
            val current = owner ?: error("no active owner")
            owner = null
            current.onSttError(error)
        }

        fun externalCancel() {
            val current = owner ?: error("no active owner")
            owner = null
            current.onSttCancelled()
        }
    }

    private fun uiListener() = object : SttListener {
        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) = Unit
        override fun onSttError(error: SttError) = Unit
    }

    @Test
    fun finalIsReturnedByStartAndStopDoesNotReplayIt() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        assertFalse(started.isCompleted)
        backend.final("мама")
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("мама"), started.await())
        assertEquals(VoiceCaptureResult.NotRecording, capture.stopCapture())
        assertEquals(0, backend.cancels)
    }

    @Test
    fun synchronousFinalIsNotLostAndBlankMeansNoSpeech() = runTest {
        val backend = FakeBackend().apply { onStart = { final("   ") } }
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        assertEquals(VoiceCaptureResult.NoSpeech, capture.startCapture("ru-RU", 60))
        assertEquals(VoiceCaptureResult.NotRecording, capture.stopCapture())
    }

    @Test
    fun startFailuresAndRecognitionErrorsAreDistinctAndPermitRetry() = runTest {
        val backend = FakeBackend().apply { startError = CaptureError.NO_PERMISSION }
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        assertEquals(VoiceCaptureResult.StartFailed(CaptureError.NO_PERMISSION), capture.startCapture("ru-RU", 60))
        backend.startError = null
        val started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        backend.error(SttError.NETWORK)
        runCurrent()
        assertEquals(VoiceCaptureResult.RecognitionFailed(SttError.NETWORK), started.await())
        val silence = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        backend.error(SttError.NO_SPEECH)
        runCurrent()
        assertEquals(VoiceCaptureResult.NoSpeech, silence.await())
    }

    @Test
    fun timeoutIsClampedAndCancelsOwnedCapture() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 1) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceCaptureResult.Timeout(5), started.await())
        assertEquals(1, backend.cancels)
        assertNull(backend.owner)
        val second = async { capture.startCapture("ru-RU", 1000) }
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(VoiceCaptureResult.Timeout(300), second.await())
        assertEquals(2, backend.cancels)
    }

    @Test
    fun cancellationBeforeAndDuringStartLeavesNoOwnedCapture() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val before = async(start = CoroutineStart.LAZY) { capture.startCapture("ru-RU", 60) }
        before.cancelAndJoin()
        assertEquals(0, backend.starts)
        val started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        started.cancelAndJoin()
        assertTrue(started.isCancelled)
        assertEquals(1, backend.cancels)
        assertNull(backend.owner)
        val retry = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        backend.final("снова")
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("снова"), retry.await())
    }

    @Test
    fun cancellationDuringStartupHandoffReleasesOwner() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        lateinit var started: kotlinx.coroutines.Deferred<VoiceCaptureResult>
        backend.onStart = { started.cancel() }
        started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        assertTrue(started.isCancelled)
        assertEquals(1, backend.cancels)
        assertNull(backend.owner)
        assertEquals(VoiceCaptureResult.NotRecording, capture.stopCapture())
    }

    @Test
    fun externalCancelCompletesStartAndDoesNotCancelNewUiCapture() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 5) }
        runCurrent()
        val old = backend.owner!!
        backend.externalCancel()
        val ui = uiListener()
        backend.owner = ui
        old.onFinal("устаревший ответ")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceCaptureResult.Cancelled, started.await())
        assertSame(ui, backend.owner)
        assertEquals(0, backend.cancels)
    }

    @Test
    fun staleTimeoutDoesNotCancelAnUnrelatedUiCapture() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 5) }
        runCurrent()
        val ui = uiListener()
        backend.owner = ui
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceCaptureResult.Timeout(5), started.await())
        assertSame(ui, backend.owner)
        assertEquals(0, backend.cancels)
    }

    @Test
    fun staleCallbackCannotCompleteNewAgentCapture() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val first = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        val old = backend.owner!!
        backend.final("первый")
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("первый"), first.await())
        val second = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        old.onFinal("неверный")
        assertFalse(second.isCompleted)
        backend.final("второй")
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("второй"), second.await())
    }

    @Test
    fun cancellingStopDoesNotCancelOwningStart() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        val stopped = async { capture.stopCapture() }
        runCurrent()
        assertEquals(1, backend.stops)
        stopped.cancelAndJoin()
        assertFalse(started.isCompleted)
        assertEquals(0, backend.cancels)
        backend.final("ответ")
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("ответ"), started.await())
    }

    @Test
    fun stopAndFinalShareOneResult() = runTest {
        val backend = FakeBackend().apply { onStop = { final("ответ") } }
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("ответ"), capture.stopCapture())
        assertEquals(VoiceCaptureResult.Transcript("ответ"), started.await())
        assertEquals(VoiceCaptureResult.NotRecording, capture.stopCapture())
    }

    @Test
    fun stopGraceTimeoutCompletesBothWaitersAndCancelsOnce() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 60) }
        runCurrent()
        val stopped = async { capture.stopCapture() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(VoiceCaptureResult.Timeout(10), stopped.await())
        assertEquals(VoiceCaptureResult.Timeout(10), started.await())
        assertEquals(1, backend.cancels)
    }

    @Test
    fun startTimeoutCompletesWaitingStopAndCancelsOnce() = runTest {
        val backend = FakeBackend()
        val capture = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { capture.startCapture("ru-RU", 5) }
        runCurrent()
        val stopped = async { capture.stopCapture() }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceCaptureResult.Timeout(5), started.await())
        assertEquals(VoiceCaptureResult.Timeout(5), stopped.await())
        assertEquals(1, backend.cancels)
    }

    @Test
    fun otherControllerCannotStopActiveCapture() = runTest {
        val backend = FakeBackend()
        val first = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val other = AgentVoiceCapture(backend, StandardTestDispatcher(testScheduler))
        val started = async { first.startCapture("ru-RU", 60) }
        runCurrent()
        assertEquals(VoiceCaptureResult.StartFailed(CaptureError.BUSY), other.startCapture("ru-RU", 60))
        assertEquals(VoiceCaptureResult.NotRecording, other.stopCapture())
        assertEquals(0, backend.stops)
        backend.final("ответ")
        runCurrent()
        assertEquals(VoiceCaptureResult.Transcript("ответ"), started.await())
    }
}
