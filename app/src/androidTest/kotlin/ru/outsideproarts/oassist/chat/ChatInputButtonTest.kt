package ru.outsideproarts.oassist.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.feature.chat.ChatInputButton

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ChatInputButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private var listening by mutableStateOf(false)
    private var captureClockMillis = 0L
    private lateinit var inputModeManager: InputModeManager
    private var visible by mutableStateOf(true)
    @Volatile private var clicks = 0
    @Volatile private var starts = 0
    @Volatile private var releases = 0
    @Volatile private var cancellations = 0
    @Volatile private var shortCaptures = 0

    @Test
    fun semanticClicksToggleWithoutCreatingAHold() {
        showButton()
        button().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(listening) }
        compose.mainClock.advanceTimeBy(2_000L)
        button().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(false, listening)
            assertEquals(2, clicks)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
    }

    @Test
    fun semanticLongClickTogglesWithoutWaitingForAPointerRelease() {
        showButton()
        button().performSemanticsAction(SemanticsActions.OnLongClick)
        compose.mainClock.advanceTimeBy(2_000L)
        compose.runOnIdle {
            assertTrue(listening)
            assertEquals(1, clicks)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
        button().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(false, listening) }
    }

    @Test
    fun tapInvokesClickWithoutStartingPushToTalk() {
        showButton()
        button().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
    }

    @Test
    fun holdSurvivesCaptureRecompositionAndReleasesOnceWithoutClickEcho() {
        showButton()
        beginHold()
        compose.runOnIdle {
            assertTrue(listening)
            assertEquals(0, releases + cancellations + clicks)
        }
        compose.runOnIdle { captureClockMillis += 550L }
        button().performTouchInput { up() }
        compose.waitUntil(5_000L) { releases == 1 }
        compose.runOnIdle {
            assertEquals(false, listening)
            assertEquals(1, starts)
            assertEquals(0, clicks + cancellations + shortCaptures)
        }
    }

    @Test
    fun cancelledGestureCancelsCaptureWithoutSendingOrClicking() {
        showButton()
        beginHold()
        compose.runOnIdle { captureClockMillis += 550L }
        button().performTouchInput { cancel() }
        compose.waitUntil(5_000L) { cancellations == 1 }
        assertEquals(0, releases + clicks + shortCaptures)
    }

    @Test
    fun tooShortCaptureAfterLongPressIsCancelledWithoutSending() {
        showButton()
        beginHold()
        button().performTouchInput { up() }
        compose.waitUntil(5_000L) { shortCaptures == 1 }
        assertEquals(0, releases + clicks + cancellations)
    }

    @Test
    fun disposalCancelsOwnedHoldOnce() {
        showButton()
        beginHold()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { cancel() }
        assertEquals(1, cancellations)
        assertEquals(0, releases + clicks + shortCaptures)
    }

    @Test
    fun disabledHoldKeepsSendActionOnRelease() {
        showButton(holdEnabled = false)
        button().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.runOnIdle { assertEquals(0, clicks + starts) }
        button().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
    }

    @Test
    fun keyboardActivationUsesToggle() {
        showButton()
        compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        button().performSemanticsAction(SemanticsActions.RequestFocus)
        button().assertIsFocused()
        button().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertTrue(listening)
            assertEquals(1, clicks)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
    }

    @Test
    fun heldKeyboardActivationTogglesWithoutStartingPointerCapture() {
        showButton()
        compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        button().performSemanticsAction(SemanticsActions.RequestFocus)
        button().assertIsFocused()
        button().performKeyInput { keyDown(Key.Enter) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitUntil(5_000L) { clicks == 1 }
        compose.runOnIdle {
            assertTrue(listening)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
        button().performKeyInput { keyUp(Key.Enter) }
        compose.runOnIdle {
            assertTrue(listening)
            assertEquals(1, clicks)
            assertEquals(0, starts + releases + cancellations + shortCaptures)
        }
    }

    private fun beginHold() {
        button().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitUntil(5_000L) { starts == 1 }
        compose.waitForIdle()
    }

    private fun button() = compose.onNodeWithTag("voice-action")

    private fun showButton(holdEnabled: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                inputModeManager = LocalInputModeManager.current
                if (visible) {
                    ChatInputButton(
                        icon = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                        label = if (listening) "Stop" else "Voice input",
                        holdLabel = "Hold to talk",
                        holdEnabled = holdEnabled,
                        canStartHold = !listening,
                        onClick = { clicks += 1; listening = !listening },
                        onHoldStart = { starts += 1; listening = true; true },
                        onHoldRelease = { releases += 1; listening = false },
                        onHoldCancel = { tooShort ->
                            if (tooShort) shortCaptures += 1 else cancellations += 1
                            listening = false
                        },
                        modifier = Modifier.testTag("voice-action"),
                        nowMillis = { captureClockMillis },
                        voiceActionLabel = if (listening) "Stop" else "Start",
                        voiceStateLabel = if (listening) "Listening" else "Idle",
                    )
                }
            }
        }
    }
}
