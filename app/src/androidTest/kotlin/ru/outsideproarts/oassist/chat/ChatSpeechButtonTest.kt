package ru.outsideproarts.oassist.chat

import android.Manifest
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.speech.BtInputDecision
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureMode
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.VoiceInputRoute
import ru.outsideproarts.oassist.core.speech.WavAudio
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.R as ChatR

@RunWith(AndroidJUnit4::class)
class ChatSpeechButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var speaking by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private val events = mutableListOf<String>()

    @Test
    fun tapStopsSpeechWithoutOpeningMicrophoneAndNextTapStartsListening() = withChat {
        speechButton().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("stop speech"), events) }
        idleButton().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("stop speech", "stop speech", "start STT"), events) }
    }

    @Test
    fun naturalSpeechCompletionRestoresVoiceInput() = withChat {
        speechButton().assertExists()
        compose.runOnIdle { speaking = false }
        idleButton().assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        idleButton().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT"), events) }
    }

    @Test
    fun holdInterruptsSpeechBeforeCaptureAndReleasesOnceAfterRecomposition() = withChat {
        val button = speechButton()
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitUntil(5_000L) { events.contains("start STT") }
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT"), events) }
        SystemClock.sleep(650L)
        captureButton().performTouchInput { advanceEventTime(650L); up() }
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT", "stop STT"), events) }
    }

    @Test
    fun cancellingHoldAfterSpeechInterruptionDoesNotRestartSpeechOrSend() = withChat {
        val button = speechButton()
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitUntil(5_000L) { events.contains("start STT") }
        captureButton().performTouchInput { cancel() }
        idleButton().assertExists()
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT", "cancel STT"), events) }
    }

    @Test
    fun semanticLongClickInterruptsSpeechAndStartsToggleInput() = withChat {
        speechButton().performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT"), events) }
        captureButton()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT", "stop speech", "stop STT"), events) }
    }

    @Test
    fun accessibilityVoiceActionInterruptsSpeechAndStartsInput() = withChat {
        val startLabel = context.getString(ChatR.string.chat_mic_start)
        val actions = speechButton().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            check(actions.single { it.label == startLabel }.action())
        }
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT"), events) }
    }

    @Test
    fun pendingHoldWithDraftSurvivesSpeechCompletionUntilRelease() = withChat(pendingCapture = true) {
        compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint)).performTextInput("draft")
        speechButton().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitUntil(5_000L) { events.contains("start STT") }
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT"), events) }
        SystemClock.sleep(650L)
        compose.onNode(hasContentDescription(context.getString(ChatR.string.chat_send)))
            .performTouchInput { advanceEventTime(650L); up() }
        compose.runOnIdle { assertEquals(listOf("stop speech", "start STT", "stop STT"), events) }
    }

    private fun speechButton() = compose.onNode(
        hasContentDescription(context.getString(ChatR.string.chat_stop_speech)),
    )

    private fun captureButton() = compose.onNode(
        hasContentDescription(context.getString(ChatR.string.chat_stop)),
    )

    private fun idleButton() = compose.onNode(
        hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true),
    )

    private fun withChat(pendingCapture: Boolean = false, test: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val original = checkNotNull(VoiceHandle.get())
        VoiceHandle.register(TestVoiceHandle(pendingCapture))
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible) {
                        ChatScreen(
                            sendMessage = { _, _, _, _, _ -> error("Unexpected message") },
                            stopSpeech = { events.add("stop speech"); speaking = false },
                            isSpeaking = speaking,
                            soundEnabled = false,
                        )
                    }
                }
            }
            test()
        } finally {
            try {
                compose.runOnIdle { visible = false }
                compose.waitForIdle()
            } finally {
                VoiceHandle.register(original)
            }
        }
    }

    private inner class TestVoiceHandle(private val pendingCapture: Boolean) : VoiceHandle {
        private val state = MutableStateFlow<CaptureState>(CaptureState.Idle)
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
            events.add("start STT")
            if (!pendingCapture) state.value = CaptureState.Listening(CaptureMode.STT, 0L)
            return null
        }
        override fun stopStt() {
            events.add("stop STT")
            state.value = CaptureState.Idle
        }
        override fun cancelStt() {
            events.add("cancel STT")
            state.value = CaptureState.Idle
        }
        override fun startRaw(maxSec: Int, bluetoothInput: Boolean): CaptureError? = error("Unexpected RAW capture")
        override fun stopRaw(): WavAudio? = error("Unexpected RAW capture")
        override fun cancelRaw(): Unit = error("Unexpected RAW capture")
    }
}
