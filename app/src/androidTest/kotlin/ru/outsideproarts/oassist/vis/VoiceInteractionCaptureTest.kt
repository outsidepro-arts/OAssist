package ru.outsideproarts.oassist.vis

import android.Manifest
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AssistSession
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.AssistantExtras
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.feature.assistant.AssistContextViewModel
import ru.outsideproarts.oassist.feature.assistant.AssistScreenHandoff

@RunWith(AndroidJUnit4::class)
class VoiceInteractionCaptureTest {
    @Test
    fun frameworkCaptureNormalDisabledSecureSettingsFailureAndNextSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Use tools/run-assist-capture-tests.ps1", "true",
            InstrumentationRegistry.getArguments().getString("assistCaptureFixture"))
        val context = instrumentation.targetContext
        val currentUser = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("am get-current-user"),
        ).bufferedReader().use { it.readText().trim() }
        check(currentUser.matches(Regex("\\d+"))) { "Cannot identify fixture user: $currentUser" }
        assertEquals(
            "Fixture needs OAssist selected as the system assistant",
            "${context.packageName}/.vis.OAssistVoiceInteractionService",
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service"),
        )
        val previousFactory = AgentFactory.get()
        val activity = AtomicReference<AssistActivity>()
        val captureToken = AtomicReference<String>()
        val result = AtomicReference<AssistSession>()
        val failure = AtomicReference<Throwable>()
        val scenarioName = AtomicReference("setup")
        val lifecycle = CopyOnWriteArrayList<String>()
        fun failureDetails(): String = buildString {
            appendLine("scenario=${scenarioName.get()} user=$currentUser uptime=${SystemClock.uptimeMillis()}")
            appendLine(runCatching {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                "root=${root?.packageName} window=${root?.windowId} overlayAllowed=${Settings.canDrawOverlays(context)} " +
                    "mic=${context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)} activeOverlay=${AssistSessionStore.activeShowOverlay()} " +
                    "pending=${captureToken.get()?.let(AssistSessionStore::isScreenCapturePending)} " +
                    "finishing=${activity.get()?.isFinishing} destroyed=${activity.get()?.isDestroyed}"
            }.getOrElse { "State inspection failed: $it" })
            appendLine(lifecycle.takeLast(32).joinToString("\n"))
            for ((command, pattern, limit) in listOf(
                Triple("appops get --user $currentUser ${context.packageName} SYSTEM_ALERT_WINDOW", Regex(".+"), 8),
                Triple("dumpsys activity activities", Regex("ResumedActivity|topResumedActivity|AssistActivity|DrawOverlay|com.android.settings"), 40),
                Triple("dumpsys window windows", Regex("mCurrentFocus|mFocusedApp|mTopFocusedDisplayId"), 8),
                Triple("dumpsys voiceinteraction", Regex("mActiveSession|mShown|mSession=|mShowFlags"), 20),
            )) {
                appendLine(command)
                appendLine(runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                        .bufferedReader().use { it.readText() }
                        .lineSequence().filter(pattern::containsMatchIn).take(limit).joinToString("\n")
                }.getOrElse { "Inspection failed: $it" })
            }
        }
        var delivered = CountDownLatch(1)
        var dismissed = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { instance, stage ->
            if (instance is AssistActivity) {
                lifecycle += "${scenarioName.get()}@${SystemClock.uptimeMillis()} id=${System.identityHashCode(instance)} " +
                    "task=${instance.taskId} stage=$stage finishing=${instance.isFinishing} changing=${instance.isChangingConfigurations}"
            }
            if (instance is AssistActivity && stage == Stage.DESTROYED && instance.isFinishing) {
                dismissed.countDown()
            }
            if (instance is AssistActivity && stage == Stage.CREATED) {
                activity.set(instance)
                try {
                    assertTrue("System did not enter through VIS", instance.intent.hasExtra(AssistScreenHandoff.EXTRA_TOKEN))
                    val retained = ViewModelProvider(instance)[AssistContextViewModel::class.java]
                    val token = requireNotNull(retained.captureToken)
                    lifecycle += "created initialized=${retained.initialized} overlayNeeded=${retained.overlayNeeded} handedOff=${retained.handedOff} " +
                        "overlayAllowed=${Settings.canDrawOverlays(context)} pending=${AssistSessionStore.isScreenCapturePending(token)}"
                    assertTrue("Capture token was not pending at Activity creation", AssistSessionStore.isScreenCapturePending(token))
                    captureToken.set(token)
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    delivered.countDown()
                }
            }
        }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
        try {
            // Repeated normal at the end proves finish releases the old framework session.
            for (scenario in listOf("normal", "disabled", "secure", "settingsFailure", "normalAgain")) {
                scenarioName.set(scenario)
                lifecycle += "before $scenario overlayAllowed=${Settings.canDrawOverlays(context)} " +
                    "mic=${context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)} activeOverlay=${AssistSessionStore.activeShowOverlay()}"
                delivered = CountDownLatch(1)
                dismissed = CountDownLatch(1)
                result.set(null)
                captureToken.set(null)
                failure.set(null)
                AgentFactory.register(object : AgentFactory by previousFactory {
                    override suspend fun modeConfig(mode: AssistMode): ModeConfig {
                        if (scenario == "settingsFailure") error("Controlled settings read failure")
                        return ModeConfig(mode = mode, assistant = AssistantExtras(
                            showOverlay = true,
                            attachScreenshot = scenario != "disabled",
                        ))
                    }
                })
                val marker = "VIS capture $scenario"
                instrumentation.context.startActivity(Intent()
                    .setClassName(instrumentation.context.packageName, AssistScreenFixtureActivity::class.java.name)
                    .putExtra("marker", marker)
                    .putExtra("secure", scenario == "secure")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                val readyBy = SystemClock.uptimeMillis() + 10_000
                while (true) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (root?.findAccessibilityNodeInfosByText(marker)?.any { it.text?.toString() == marker } == true) {
                        // Accessibility can expose text before the launch transition has drawn its surface.
                        // The preceding fixture has the same background, so its old surface is insufficient.
                        instrumentation.uiAutomation.waitForIdle(500, 5_000)
                        val visible = instrumentation.uiAutomation.takeScreenshot()
                        val pixel = visible?.getPixel(visible.width / 2, visible.height / 2)
                        visible?.recycle()
                        if (scenario == "secure") {
                            if (!isFixturePixel(pixel)) break
                        } else if (isFixturePixel(pixel)) break
                    }
                    check(SystemClock.uptimeMillis() < readyBy) { "Fixture screen not visible: $scenario" }
                    SystemClock.sleep(50)
                }
                // The assistant role can restore overlay access between independent captures.
                // Keep each scenario on the denied-permission Activity handoff path.
                val overlayOutput = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("appops set --user $currentUser ${context.packageName} SYSTEM_ALERT_WINDOW deny"),
                ).bufferedReader().use { it.readText() }
                assertTrue("Could not disable fixture overlay access: $overlayOutput", overlayOutput.isBlank())
                assertFalse("Overlay access must be denied for $scenario", Settings.canDrawOverlays(context))
                val output = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_ASSIST"),
                ).bufferedReader().use { it.readText() }
                assertFalse(output, output.contains("Error", ignoreCase = true))
                if (!delivered.await(10, TimeUnit.SECONDS)) throw AssertionError("No VIS handoff for $scenario\n${failureDetails()}")
                failure.get()?.let { throw AssertionError("Handoff failed for $scenario\n${failureDetails()}", it) }
                // Keep the permission Activity's token valid until its Settings launch completes.
                val settingsBy = SystemClock.uptimeMillis() + 10_000
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.settings") {
                    check(SystemClock.uptimeMillis() < settingsBy) { "Permission screen not shown for $scenario\n${failureDetails()}" }
                    SystemClock.sleep(50)
                }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val token = requireNotNull(captureToken.get())
                instrumentation.runOnMainSync {
                    result.set(AssistSessionStore.consumeScreenCapture(token, true))
                }
                val captured = requireNotNull(result.get()) { "Capture token expired before inspection for $scenario" }
                if (scenario != "secure") assertTrue("Missing tree for $scenario", captured.screenContext.orEmpty().contains(marker))
                if (scenario == "disabled" || scenario == "settingsFailure") {
                    assertNull(captured.screenImage)
                } else {
                    val bitmap = captured.screenImage?.let(ImageCodec::toBitmap)
                    val pixel = bitmap?.getPixel(bitmap.width / 2, bitmap.height / 2)
                    val showsFixture = isFixturePixel(pixel)
                    val metadata = "image=${captured.screenImage?.width}x${captured.screenImage?.height}, " +
                        "bitmap=${bitmap?.width}x${bitmap?.height}, center=${pixel?.toUInt()?.toString(16)}"
                    bitmap?.recycle()
                    if (scenario == "secure") assertFalse("FLAG_SECURE pixels leaked", showsFixture)
                    else assertTrue("Screenshot missing or captured after switching UI: $scenario ($metadata)", showsFixture)
                }
                // Complete the real permission Activity lifecycle before launching the next fixture.
                // Settings may redirect to a second Activity after its original launch returned.
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                if (!dismissed.await(10, TimeUnit.SECONDS)) throw AssertionError("Permission Activity not dismissed for $scenario\n${failureDetails()}")
            }
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                activity.get()?.finish()
            }
            AgentFactory.register(previousFactory)
            AssistSessionStore.clear()
        }
    }

    private fun isFixturePixel(pixel: Int?): Boolean =
        pixel != null && Color.red(pixel) > 180 && Color.blue(pixel) > 180 && Color.green(pixel) < 80
}
