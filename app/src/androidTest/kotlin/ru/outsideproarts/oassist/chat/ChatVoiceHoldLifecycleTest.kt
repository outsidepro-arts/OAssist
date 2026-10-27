package ru.outsideproarts.oassist.chat

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.speech.BtInputDecision
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.VoiceInputRoute
import ru.outsideproarts.oassist.core.speech.WavAudio
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.R as ChatR

@RunWith(AndroidJUnit4::class)
class ChatVoiceHoldLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cancelledHoldCancelsAcceptedSttWhilePublicCaptureStateIsStillIdle() {
        verifyCancelledHold(finalBeforeCancel = false)
    }

    @Test
    fun finalBeforeReleaseIsDiscardedWhenTheGestureIsCancelled() {
        verifyCancelledHold(finalBeforeCancel = true)
    }

    private fun verifyCancelledHold(finalBeforeCancel: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val original = checkNotNull(VoiceHandle.get())
        val pendingVoice = PendingVoiceHandle()
        val component = "example.speech/example.speech.Recognizer"
        val sends = java.util.concurrent.atomic.AtomicInteger()
        var visible by mutableStateOf(true)
        VoiceHandle.register(pendingVoice)
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible) {
                        ChatScreen(
                            sendMessage = { _, _, _, _, _ ->
                                sends.incrementAndGet()
                                error("Cancelled capture must not send a message")
                            },
                            stt = SttConfig(recognizerComponent = component),
                        )
                    }
                }
            }
            val button = compose.onNode(
                hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true),
            )
            button.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitUntil(5_000L) { pendingVoice.starts == 1 }
            compose.waitForIdle()
            assertEquals(CaptureState.Idle, pendingVoice.state.value)
            assertEquals(component, pendingVoice.component)
            if (finalBeforeCancel) {
                compose.runOnIdle { checkNotNull(pendingVoice.listener).onFinal("uncommitted transcript") }
                compose.waitForIdle()
                assertEquals(0, sends.get())
            }

            button.performTouchInput { cancel() }
            compose.waitForIdle()

            assertEquals(if (finalBeforeCancel) 0 else 1, pendingVoice.cancellations)
            assertEquals(0, pendingVoice.stops)
            assertEquals(0, sends.get())
        } finally {
            try {
                compose.runOnIdle { visible = false }
                compose.waitForIdle()
            } finally {
                VoiceHandle.register(original)
            }
        }
    }

    private class PendingVoiceHandle : VoiceHandle {
        val state = MutableStateFlow<CaptureState>(CaptureState.Idle)
        @Volatile var starts = 0
        @Volatile var cancellations = 0
        @Volatile var stops = 0
        @Volatile var component: String? = null
        var listener: SttListener? = null

        override fun captureState() = state
        override fun routeState() = MutableStateFlow(VoiceInputRoute.MICROPHONE)
        override fun isSttAvailable(recognizerComponent: String?) = true
        override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
        override fun markBluetoothPermissionRequested() = Unit
        override fun startStt(
            language: String,
            listener: SttListener,
            recognizerComponent: String?,
            bluetoothInput: Boolean,
        ): CaptureError? {
            component = recognizerComponent
            this.listener = listener
            starts += 1
            return null
        }
        override fun stopStt() { stops += 1 }
        override fun cancelStt() { cancellations += 1 }
        override fun startRaw(maxSec: Int, bluetoothInput: Boolean): CaptureError? = error("Unexpected RAW capture")
        override fun stopRaw(): WavAudio? = error("Unexpected RAW capture")
        override fun cancelRaw(): Unit = error("Unexpected RAW capture")
    }
}
