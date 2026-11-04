package ru.outsideproarts.oassist.vis

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.speech.*
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService
import ru.outsideproarts.oassist.feature.assistant.AssistScreenHandoff

@RunWith(AndroidJUnit4::class)
class AssistServiceHandoffTest {
    @Test
    fun activityHandoffServiceReplayCommandsAndRestartKeepOwnerBoundaries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Use tools/run-assist-capture-tests.ps1", "true",
            InstrumentationRegistry.getArguments().getString("assistCaptureFixture"))
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue("Fixture needs notifications enabled", notifications.areNotificationsEnabled())
        assertEquals("Fixture needs microphone permission", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val previousFactory = AgentFactory.get()
        val previousVoice = requireNotNull(VoiceHandle.get())
        val previousNetwork = requireNotNull(NetworkHandle.get())
        val starts = AtomicInteger()
        val cancels = AtomicInteger()
        val service = Intent(context, AssistantOverlayService::class.java)
        fun offlineNotice(): Boolean = notifications.activeNotifications.any {
            it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                context.getString(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
        }
        fun reply() {
            val command = Intent(service).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_REPLY)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AssistantOverlayService.EXTRA_REPLY).build()), command,
                Bundle().apply { putCharSequence(AssistantOverlayService.EXTRA_REPLY, "fixture question") })
            context.startForegroundService(command)
        }
        fun publish(text: String?, image: ImagePart?): String {
            val token = AtomicReference<String>()
            instrumentation.runOnMainSync {
                token.set(AssistScreenHandoff.publish(AssistSessionStore.beginScreenCapture(), text, image))
            }
            return requireNotNull(token.get())
        }
        try {
            AssistSessionStore.clear()
            AgentFactory.register(object : AgentFactory by previousFactory {
                override suspend fun modeConfig(mode: AssistMode) = ModeConfig(
                    mode = mode, soundEnabled = false, stt = SttConfig(bluetoothInput = false),
                    assistant = AssistantExtras(showOverlay = false, attachScreenshot = true),
                )
            })
            NetworkHandle.register(object : NetworkHandle { override val isOnline = MutableStateFlow(false) })
            VoiceHandle.register(object : VoiceHandle by previousVoice {
                private var sttOwner: SttListener? = null
                override fun cancelStt(owner: SttListener): Boolean {
                    if (sttOwner !== owner) return false
                    sttOwner = null
                    cancelStt()
                    return true
                }
                override fun isSttAvailable(recognizerComponent: String?) = true
                override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
                override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): CaptureError? {
                    sttOwner = listener
                    starts.incrementAndGet()
                    return null
                }
                override fun cancelStt() { cancels.incrementAndGet() }
                override fun stopStt() = Unit
            })
            val image = ImagePart("large-payload-".repeat(100_000))
            val token = publish("activity context", image)
            context.startActivity(Intent(context, AssistActivity::class.java)
                .putExtra(AssistScreenHandoff.EXTRA_TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { starts.get() > 0 && AssistSessionStore.current()?.screenContext == "activity context" }
            val owner = requireNotNull(AssistSessionStore.current())
            assertSame(image, owner.screenImage)
            owner.appendTurn(ChatMessage(ChatRole.USER, "retained history"), emptyList())

            // A replayed Intent reaches the actual running Service, then a valid empty snapshot follows it.
            context.startService(Intent(service).putExtra(AssistScreenHandoff.EXTRA_TOKEN, token))
            val empty = publish(null, null)
            context.startService(Intent(service).putExtra(AssistScreenHandoff.EXTRA_TOKEN, empty))
            await { !AssistSessionStore.isScreenCapturePending(empty) }
            assertSame(owner, AssistSessionStore.current())
            assertNull(owner.screenContext)
            assertNull(owner.screenImage)
            assertEquals("retained history", owner.history.single().content)

            val current = publish("command context", image)
            context.startService(Intent(service).putExtra(AssistScreenHandoff.EXTRA_TOKEN, current))
            await { owner.screenContext == "command context" }
            val cancelledBefore = cancels.get()
            context.startService(Intent(service).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_MIC))
            await { cancels.get() > cancelledBefore }
            reply()
            await(::offlineNotice)
            assertSame(owner, AssistSessionStore.current())
            assertEquals("command context", owner.screenContext)
            assertSame(image, owner.screenImage)
            assertEquals("retained history", owner.history.single().content)

            val lateToken = publish("stale owner A", image)
            context.stopService(service)
            await { AssistSessionStore.current() == null }
            val startsBeforeReplacement = starts.get()
            val replacementToken = publish("owner B", null)
            context.startForegroundService(Intent(service).putExtra(AssistScreenHandoff.EXTRA_TOKEN, replacementToken))
            await { AssistSessionStore.current()?.screenContext == "owner B" && starts.get() > startsBeforeReplacement }
            val replacement = requireNotNull(AssistSessionStore.current())
            replacement.appendTurn(ChatMessage(ChatRole.USER, "owner B history"), emptyList())
            val cancelledBeforeLateIntent = cancels.get()
            context.startService(Intent(service).putExtra(AssistScreenHandoff.EXTRA_TOKEN, lateToken))
            // The subsequent command is an observable barrier for the preceding stale Intent.
            context.startService(Intent(service).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_MIC))
            await { cancels.get() > cancelledBeforeLateIntent }
            assertSame(replacement, AssistSessionStore.current())
            assertEquals("owner B", replacement.screenContext)
            assertNull(replacement.screenImage)
            assertEquals("owner B history", replacement.history.single().content)
            context.stopService(service)
            await { AssistSessionStore.current() == null }

            // A notification command can recreate the Service without the normal activation branch.
            reply()
            await { AssistSessionStore.current() != null && offlineNotice() }
            val restarted = requireNotNull(AssistSessionStore.current())
            assertNotSame(owner, restarted)
            assertNull(restarted.screenContext)
            assertTrue(restarted.history.isEmpty())
            context.stopService(service)
            await { AssistSessionStore.current() == null }
        } finally {
            context.stopService(service)
            instrumentation.waitForIdleSync()
            VoiceHandle.register(previousVoice)
            NetworkHandle.register(previousNetwork)
            AgentFactory.register(previousFactory)
            AssistSessionStore.clear()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Service did not reach expected state" }
            SystemClock.sleep(25)
        }
    }
}
