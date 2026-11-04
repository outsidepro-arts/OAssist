package ru.outsideproarts.oassist.chat

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.speech.*
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.observeChatDestinationExit
import ru.outsideproarts.oassist.feature.chat.R as ChatR

@RunWith(AndroidJUnit4::class)
class ChatToggleNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleTopChatCanStartAnotherToggle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val original = checkNotNull(VoiceHandle.get())
        val voice = TestVoice()
        lateinit var reselect: () -> Unit
        VoiceHandle.register(voice)
        try {
            compose.setContent {
                val nav = rememberNavController()
                reselect = { nav.navigate("chat") { launchSingleTop = true } }
                MaterialTheme {
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("chat") { entry ->
                            ChatScreen(
                                sendMessage = { _, _, _, _, _ -> error("Unexpected send") },
                                registerDestinationExit = remember(nav, entry) {
                                    { onExit -> observeChatDestinationExit(nav, entry, onExit) }
                                },
                            )
                        }
                    }
                }
            }
            val button = compose.onNode(hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true))
            button.performClick()
            compose.waitUntil(5_000L) { voice.starts == 1 }
            compose.runOnIdle { reselect() }
            compose.waitForIdle()
            button.performClick()
            compose.waitUntil(5_000L) { voice.starts == 2 }
            assertEquals(2, voice.starts)
        } finally {
            VoiceHandle.register(original)
        }
    }

    @Test fun outgoingChatRejectsFinalBeforeExitAnimationFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val original = checkNotNull(VoiceHandle.get())
        val voice = TestVoice()
        val sends = AtomicInteger()
        var outgoingComposed = false
        lateinit var navigateAway: () -> Unit
        VoiceHandle.register(voice)
        try {
            compose.setContent {
                val nav = rememberNavController()
                navigateAway = { nav.navigate("settings") }
                MaterialTheme {
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("chat") { entry ->
                            DisposableEffect(Unit) {
                                outgoingComposed = true
                                onDispose { outgoingComposed = false }
                            }
                            ChatScreen(
                                sendMessage = { _, _, _, _, _ ->
                                    sends.incrementAndGet()
                                    error("Outgoing chat must not send")
                                },
                                registerDestinationExit = remember(nav, entry) {
                                    { onExit -> observeChatDestinationExit(nav, entry, onExit) }
                                },
                            )
                        }
                        composable("settings") { }
                    }
                }
            }
            compose.onNode(hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true))
                .performClick()
            compose.waitUntil(5_000L) { voice.listener != null }
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                val stale = checkNotNull(voice.listener)
                navigateAway()
                assertTrue("chat must still be composed during exit", outgoingComposed)
                stale.onFinal("late transcript")
                assertEquals(0, sends.get())
                assertEquals(1, voice.cancellations)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            VoiceHandle.register(original)
        }
    }

    internal class TestVoice : VoiceHandle {
        @Volatile var starts = 0
        var listener: SttListener? = null
        var cancellations = 0
        override fun captureState() = MutableStateFlow<CaptureState>(CaptureState.Idle)
        override fun routeState() = MutableStateFlow(VoiceInputRoute.MICROPHONE)
        override fun isSttAvailable(recognizerComponent: String?) = true
        override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
        override fun markBluetoothPermissionRequested() = Unit
        override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): CaptureError? {
            this.listener = listener
            starts++
            return null
        }
        override fun stopStt() = Unit
        override fun cancelStt() = error("Unexpected global cancellation")
        override fun cancelStt(owner: SttListener): Boolean {
            if (listener !== owner) return false
            listener = null
            cancellations++
            return true
        }
        override fun startRaw(maxSec: Int, bluetoothInput: Boolean): CaptureError? = error("Unexpected RAW capture")
        override fun stopRaw(): WavAudio? = error("Unexpected RAW capture")
        override fun cancelRaw() = error("Unexpected RAW capture")
    }
}
