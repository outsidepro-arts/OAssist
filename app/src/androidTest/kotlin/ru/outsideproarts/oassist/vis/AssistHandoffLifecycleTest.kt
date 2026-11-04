package ru.outsideproarts.oassist.vis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.feature.assistant.AssistScreenHandoff

@RunWith(AndroidJUnit4::class)
class AssistHandoffLifecycleTest {
    @Test fun latePermissionCannotUpdateReplacementOwner() = verifyLatePermission(false)
    @Test fun latePermissionCannotRecreateOwnerAfterNullAba() = verifyLatePermission(true)

    private fun verifyLatePermission(nullAba: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Use tools/run-assist-capture-tests.ps1", "true",
            InstrumentationRegistry.getArguments().getString("assistCaptureFixture"))
        val context = instrumentation.targetContext
        assertFalse(Settings.canDrawOverlays(context))
        val user = shell("am get-current-user").trim().also { require(it.matches(Regex("[0-9]+"))) }
        val operation = "--user $user ${context.packageName} SYSTEM_ALERT_WINDOW"
        val originalMode = Regex("SYSTEM_ALERT_WINDOW: (\\w+)").find(shell("appops get $operation"))
            ?.groupValues?.get(1) ?: "default"
        val activity = AtomicReference<AssistActivity>()
        val finished = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { instance, stage ->
            if (instance is AssistActivity) {
                if (stage == Stage.CREATED) activity.set(instance)
                if (stage == Stage.DESTROYED && instance.isFinishing) finished.countDown()
            }
        }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
        try {
            AssistSessionStore.clear()
            val token = AtomicReference<String>()
            instrumentation.runOnMainSync {
                token.set(AssistScreenHandoff.publish(
                    AssistSessionStore.beginScreenCapture(), "old context", ImagePart("old image"),
                ))
            }
            context.startActivity(Intent(context, AssistActivity::class.java)
                .putExtra(AssistScreenHandoff.EXTRA_TOKEN, token.get())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val readyBy = SystemClock.uptimeMillis() + 10_000
            while (activity.get() == null ||
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.settings"
            ) {
                check(SystemClock.uptimeMillis() < readyBy) { "Overlay permission screen did not open" }
                SystemClock.sleep(50)
            }
            AssistSessionStore.clear()
            val replacement = AssistSessionStore.getOrCreate()
            replacement.screenContext = "new context"
            replacement.screenImage = ImagePart("new image")
            if (nullAba) AssistSessionStore.clear()
            shell("appops set $operation allow")
            assertTrue(Settings.canDrawOverlays(context))
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            assertTrue("Stale permission owner did not finish", finished.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            if (nullAba) assertNull(AssistSessionStore.current()) else {
                assertSame(replacement, AssistSessionStore.current())
                assertEquals("new context", replacement.screenContext)
                assertEquals("new image", replacement.screenImage?.imageBase64)
            }
            assertFalse(AssistSessionStore.isScreenCapturePending(token.get()))
        } finally {
            shell("appops set $operation $originalMode")
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                activity.get()?.finish()
            }
            AssistSessionStore.clear()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
