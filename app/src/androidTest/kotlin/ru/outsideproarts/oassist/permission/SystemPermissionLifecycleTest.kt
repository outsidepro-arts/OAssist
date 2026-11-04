package ru.outsideproarts.oassist.permission

import android.Manifest
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcel
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome
import ru.outsideproarts.oassist.feature.assistant.ConsentActivity
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.feature.assistant.AssistContextViewModel

@RunWith(AndroidJUnit4::class)
class SystemPermissionLifecycleTest {
    @Test
    fun assistWithoutRetainedOwnerFinishesAfterRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(Settings.canDrawOverlays(context))
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
            context.startActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForPackage(instrumentation, "com.android.settings")
            instrumentation.runOnMainSync {
                activity.get().viewModelStore.clear()
                activity.get().recreate()
            }
            assertTrue("AssistActivity did not fail closed", finished.await(10, TimeUnit.SECONDS))
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                activity.get()?.finish()
            }
        }
    }

    @Test
    fun overlaySettingsSurviveAssistRecreationAndReturnWithoutGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Fixture requires overlay access to be off", Settings.canDrawOverlays(context))
        val activity = AtomicReference<AssistActivity>()
        val created = CountDownLatch(2)
        val finished = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { instance, stage ->
            if (instance is AssistActivity) {
                if (stage == Stage.CREATED) {
                    activity.set(instance)
                    created.countDown()
                } else if (stage == Stage.DESTROYED && instance.isFinishing) {
                    finished.countDown()
                }
            }
        }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
        try {
            context.startActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForPackage(instrumentation, "com.android.settings")
            val contextValue = "unique-assist-context"
            val imageValue = "image-data-".repeat(200_000)
            instrumentation.runOnMainSync {
                val owner = activity.get()
                val retained = ViewModelProvider(owner)[AssistContextViewModel::class.java]
                retained.screenContext = contextValue
                retained.screenImage = imageValue
                setActivityField(owner, "screenContext", contextValue)
                setActivityField(owner, "screenImage", imageValue)
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(owner, state)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(state)
                    assertTrue("Saved state contains a large payload", parcel.dataSize() < 100_000)
                } finally {
                    parcel.recycle()
                }
                owner.recreate()
            }
            assertTrue("AssistActivity was not recreated", created.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val retained = ViewModelProvider(activity.get())[AssistContextViewModel::class.java]
                assertEquals(contextValue, retained.screenContext)
                assertEquals(imageValue, retained.screenImage)
                assertEquals(contextValue, getActivityField(activity.get(), "screenContext"))
                assertEquals(imageValue, getActivityField(activity.get(), "screenImage"))
            }
            waitForPackage(instrumentation, "com.android.settings")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            assertTrue("AssistActivity did not finish after return", finished.await(10, TimeUnit.SECONDS))
            assertFalse(Settings.canDrawOverlays(context))
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                activity.get()?.finish()
            }
        }
    }

    @Test
    fun systemDialogSurvivesRecreationAndReturnsDenial() = verifyDialog(
        Manifest.permission.RECORD_AUDIO,
        "permission_deny_button",
        PermissionRequestOutcome.DENIED,
    )

    @Test
    fun systemDialogSurvivesRecreationAndReturnsGrant() = verifyDialog(
        Manifest.permission.CAMERA,
        "permission_allow_foreground_only_button",
        PermissionRequestOutcome.GRANTED,
    )

    private fun verifyDialog(permission: String, button: String, expected: PermissionRequestOutcome) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            "Use tools/run-system-permission-tests.ps1 to prepare system permissions",
            "true",
            InstrumentationRegistry.getArguments().getString("systemPermissionFixture"),
        )
        assertEquals(
            "Use tools/run-system-permission-tests.ps1 to prepare system permissions",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(permission),
        )
        val permissions = listOf(permission)
        val buttonId = "com.android.permissioncontroller:id/$button"
        val id = PermissionRequestBridge.register(permissions)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            PermissionRequestBridge.await(id, userTimeoutMs = 30_000)
        }
        val activity = AtomicReference<ConsentActivity>()
        val created = CountDownLatch(2)
        val callback = ActivityLifecycleCallback { instance, stage ->
            if (instance is ConsentActivity && stage == Stage.CREATED) {
                activity.set(instance)
                created.countDown()
            }
        }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
        try {
            context.startActivity(
                ConsentActivity.permissionIntent(context, id, permissions)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            waitForButton(buttonId)
            assertFalse(result.isCompleted)
            instrumentation.runOnMainSync { activity.get().recreate() }
            assertTrue("Activity was not recreated", created.await(10, TimeUnit.SECONDS))
            assertFalse(result.isCompleted)
            assertTrue(waitForButton(buttonId)
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(expected, withTimeout(10_000) { result.await() })
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                activity.get()?.finish()
            }
            PermissionRequestBridge.cancel(id)
        }
    }

    private fun waitForButton(id: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            if (node != null) return node
            Thread.sleep(100)
        }
        throw AssertionError("System permission button not found: $id")
    }

    private fun waitForPackage(instrumentation: Instrumentation, name: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == name) return
            Thread.sleep(100)
        }
        throw AssertionError("System window not found: $name")
    }

    private fun setActivityField(activity: AssistActivity, name: String, value: String) {
        AssistActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun getActivityField(activity: AssistActivity, name: String): Any? =
        AssistActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
