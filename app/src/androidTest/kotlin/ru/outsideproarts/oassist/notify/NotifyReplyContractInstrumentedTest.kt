package ru.outsideproarts.oassist.notify

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.NotifyActions
import ru.outsideproarts.oassist.core.agent.NotifyReplyTool
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Run through the host permission runner, which owns snapshots and restoration. */
@RunWith(AndroidJUnit4::class)
class NotifyReplyContractInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixture = ComponentName(instrumentation.context.packageName, ReplyNotificationFixture::class.java.name)

    @Test
    fun ascii500ReachesExternalReceiverIntact() = withNotification { key, replies ->
        assertReply(key, "x".repeat(500), replies)
    }

    @Test
    fun unicode500IncludingSurrogateBoundaryReachesExternalReceiverIntact() = withNotification { key, replies ->
        assertReply(key, "x".repeat(499) + "😀", replies)
        assertReply(key, "😀".repeat(500), replies)
    }

    @Test
    fun whitespaceAndCombiningCharactersArePreserved() = withNotification { key, replies ->
        assertReply(key, " \nПривет е\u0301 👩‍💻\t ", replies)
    }

    @Test
    fun oversizedAndBlankRepliesDoNotDispatch() = withNotification { key, replies ->
        val tool = replyTool()
        for (text in listOf("x".repeat(501), "😀".repeat(501), "x".repeat(499) + "😀x", " \n\t")) {
            val result = runBlocking { tool.execute(arguments(key, text)) }
            assertFalse(result.output, result.success)
        }
        // A valid reply is a delivery barrier: the first received payload must be this one.
        assertReply(key, "after-rejections", replies)
        assertTrue(replies.isEmpty())
    }

    @Test
    fun canceledPendingIntentReportsFailure() = withNotification(cancelPendingIntent = true) { key, replies ->
        val result = runBlocking { replyTool().execute(arguments(key, "text")) }
        assertFalse(result.output, result.success)
        assertTrue(result.output.contains("Не удалось отправить ответ"))
        assertTrue(replies.isEmpty())
    }

    private fun replyTool() = NotifyReplyTool(NotifyActions(serviceProvider = { OAssistNotificationListener.instance }))

    private fun arguments(key: String, text: String) = buildJsonObject {
        put("key", key)
        put("text", text)
    }

    private fun assertReply(key: String, text: String, replies: LinkedBlockingQueue<Bundle>) {
        val result = runBlocking { replyTool().execute(arguments(key, text)) }
        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("доставка адресату не подтверждена"))
        val received = replies.poll(5, TimeUnit.SECONDS)
        assertNotNull("External receiver did not receive RemoteInput", received)
        assertEquals(text, received!!.getString("text"))
    }

    private fun withNotification(
        cancelPendingIntent: Boolean = false,
        block: (String, LinkedBlockingQueue<Bundle>) -> Unit,
    ) {
        val user = InstrumentationRegistry.getArguments().getString("notificationReplyUser")
        require(user != null && user.matches(Regex("\\d+"))) { "Run through tools/run-system-permission-tests.ps1" }
        if (!OAssistNotificationListener.isEnabled(context)) {
            // Grant only after am instrument has restarted the target process.
            // The host snapshots/restores this one component, including on test failure.
            val listener = ComponentName(context, OAssistNotificationListener::class.java).flattenToString()
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd notification allow_listener $listener $user")
            AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        runBlocking {
            withTimeout(5_000) {
                while (!OAssistNotificationListener.isEnabled(context) || OAssistNotificationListener.instance == null) delay(20)
            }
        }
        val replies = LinkedBlockingQueue<Bundle>()
        val callback = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                replies.add(resultData)
            }
        }
        try {
            command("post") {
                putExtra("replies", callback)
                putExtra("cancelPendingIntent", cancelPendingIntent)
            }
            val key = runBlocking {
                try {
                    withTimeout(5_000) {
                        var key: String? = null
                        while (key == null) {
                            key = OAssistNotificationListener.instance?.activeNotifications?.firstOrNull {
                                it.packageName == fixture.packageName && it.tag == ReplyNotificationFixture.CHANNEL
                            }?.key
                            if (key == null) delay(20)
                        }
                        key
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    val listener = OAssistNotificationListener.instance
                    val active = listener?.activeNotifications?.joinToString { "${it.packageName}:${it.tag}" }
                    throw AssertionError("Notification not observed: listener=${listener != null}, active=$active, expected=${fixture.packageName}:${ReplyNotificationFixture.CHANNEL}", error)
                }
            }
            block(key, replies)
        } finally {
            command("clear")
        }
    }

    private fun command(operation: String, configure: Intent.() -> Unit = {}) {
        val completion = LinkedBlockingQueue<Pair<Int, Bundle>>()
        val callback = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                completion.add(resultCode to resultData)
            }
        }
        context.sendBroadcast(Intent(operation).setComponent(fixture).apply {
            putExtra("completion", callback)
            configure()
        })
        val result = completion.poll(5, TimeUnit.SECONDS)
        assertNotNull("Fixture did not acknowledge $operation", result)
        assertEquals(result!!.second.getString("error"), 1, result.first)
    }
}
