package ru.outsideproarts.oassist.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.speech.*

class ChatToggleSttOwnerTest {
    private class FakeVoice : VoiceHandle {
        var current: SttListener? = null
        var failure: CaptureError? = null
        var cancelled = 0
        var duringCancel: (() -> Unit)? = null
        override fun captureState() = MutableStateFlow<CaptureState>(CaptureState.Idle)
        override fun routeState() = MutableStateFlow(VoiceInputRoute.MICROPHONE)
        override fun isSttAvailable(recognizerComponent: String?) = true
        override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
        override fun markBluetoothPermissionRequested() = Unit
        override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): CaptureError? {
            if (failure != null) return failure
            current = listener
            return null
        }
        override fun stopStt() = Unit
        override fun cancelStt() { error("global cancel must not be used") }
        override fun cancelStt(owner: SttListener): Boolean {
            duringCancel?.invoke()
            if (current !== owner) return false
            current = null
            cancelled++
            owner.onSttCancelled()
            return true
        }
        override fun startRaw(maxSec: Int, bluetoothInput: Boolean): CaptureError? = null
        override fun stopRaw(): WavAudio? = null
        override fun cancelRaw() = Unit
    }

    private class Results : SttListener {
        val sent = mutableListOf<String>()
        val partial = mutableListOf<String>()
        var errors = 0
        override fun onPartial(text: String) { partial.add(text) }
        override fun onFinal(text: String) { sent.add(text) }
        override fun onSttError(error: SttError) { errors++ }
    }

    @Test fun disposeInvalidatesCallbacksBeforeCancellingCapture() {
        val voice = FakeVoice()
        val results = Results()
        val owner = ChatToggleSttOwner(voice)
        owner.start("en", results, null, false)
        val listener = voice.current!!
        voice.duringCancel = { listener.onFinal("during cancellation") }
        owner.dispose()
        listener.onPartial("late")
        listener.onFinal("late")
        listener.onSttError(SttError.NETWORK)
        owner.dispose()
        assertTrue(results.sent.isEmpty())
        assertTrue(results.partial.isEmpty())
        assertEquals(0, results.errors)
        assertEquals(1, voice.cancelled)
    }

    @Test fun staleScreenCannotCancelNewOwnerAndReopenedScreenWorks() {
        val voice = FakeVoice()
        val old = ChatToggleSttOwner(voice)
        val oldResults = Results()
        old.start("en", oldResults, null, false)
        val stale = voice.current!!
        val fresh = ChatToggleSttOwner(voice)
        val freshResults = Results()
        fresh.start("en", freshResults, null, false)
        val current = voice.current!!
        old.dispose()
        stale.onFinal("old")
        assertSame(current, voice.current)
        current.onFinal("new")
        current.onFinal("duplicate")
        assertTrue(oldResults.sent.isEmpty())
        assertEquals(listOf("new"), freshResults.sent)
        fresh.dispose()
        assertEquals(0, voice.cancelled)
    }

    @Test fun failedStartDoesNotOwnForeignCapture() {
        val voice = FakeVoice()
        val foreign = Results()
        voice.current = foreign
        voice.failure = CaptureError.BUSY
        val owner = ChatToggleSttOwner(voice)
        assertEquals(CaptureError.BUSY, owner.start("en", Results(), null, false))
        owner.dispose()
        assertSame(foreign, voice.current)
        assertEquals(0, voice.cancelled)
    }

    @Test fun resumeAllowsNewCaptureButDoesNotReviveOldCallbacks() {
        val voice = FakeVoice()
        val owner = ChatToggleSttOwner(voice)
        val results = Results()
        owner.start("en", results, null, false)
        val old = voice.current!!
        owner.pause()
        owner.resume()
        owner.start("en", results, null, false)
        val current = voice.current!!
        old.onFinal("stale")
        current.onFinal("current")
        assertEquals(listOf("current"), results.sent)
    }

    @Test fun permissionResultBeforeResumeStartsOnlyOnceAndDisposeDiscardsPending() {
        val owner = ChatToggleSttOwner(FakeVoice())
        var starts = 0
        owner.pause()
        owner.whenActive { starts++ }
        assertEquals(0, starts)
        owner.resume()
        owner.resume()
        assertEquals(1, starts)
        owner.pause()
        owner.whenActive { starts++ }
        owner.dispose()
        owner.resume()
        assertEquals(1, starts)
    }

    @Test fun leavingDestinationDiscardsPermissionResultBeforeResume() {
        val owner = ChatToggleSttOwner(FakeVoice())
        var starts = 0
        owner.pause()
        owner.whenActive { starts++ }
        owner.leaveDestination()
        owner.whenActive { starts++ }
        owner.resume()
        assertEquals(0, starts)
    }
}
