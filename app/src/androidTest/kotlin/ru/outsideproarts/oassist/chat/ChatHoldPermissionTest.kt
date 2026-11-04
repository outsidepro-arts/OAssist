package ru.outsideproarts.oassist.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.R as ChatR

@RunWith(AndroidJUnit4::class)
class ChatHoldPermissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun releasedHoldDoesNotStartAfterPermissionGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Use tools/run-system-permission-tests.ps1", "true", InstrumentationRegistry.getArguments().getString("systemPermissionFixture"))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val original = checkNotNull(VoiceHandle.get())
        val voice = ChatToggleNavigationTest.TestVoice()
        lateinit var chatView: View
        var pendingCancel: MotionEvent? = null
        VoiceHandle.register(voice)
        try {
            compose.setContent {
                chatView = LocalView.current
                MaterialTheme { ChatScreen(sendMessage = { _, _, _, _, _ -> error("Unexpected send") }) }
            }
            val mic = compose.onNode(hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true))
            val bounds = mic.fetchSemanticsNode().boundsInRoot
            val x = bounds.center.x
            val y = bounds.center.y
            val downTime = SystemClock.uptimeMillis()
            mic.performTouchInput { down(center) }
            pendingCancel = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_CANCEL, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            compose.mainClock.advanceTimeBy(1_000L)
            val deadline = System.currentTimeMillis() + 10_000
            var grant: AccessibilityNodeInfo? = null
            while (grant == null && System.currentTimeMillis() < deadline) {
                grant = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_foreground_only_button")
                    ?.firstOrNull()
                if (grant == null) Thread.sleep(100)
            }
            assertTrue("Permission grant button not found", grant != null)
            // Model the interrupted gesture while the system dialog owns the window.
            instrumentation.runOnMainSync { chatView.dispatchTouchEvent(checkNotNull(pendingCancel)) }
            pendingCancel?.recycle()
            pendingCancel = null
            assertTrue(checkNotNull(grant).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(10_000L) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
            compose.waitForIdle()
            assertEquals(0, voice.starts)
            // Clear the test dispatcher after the view received CANCEL.
            mic.performTouchInput { cancel() }
            assertEquals(0, voice.starts)
            mic.performClick()
            compose.waitUntil(10_000L) { voice.starts > 0 }
            compose.waitForIdle()
            assertEquals(1, voice.starts)
        } finally {
            pendingCancel?.let { cancel ->
                instrumentation.runOnMainSync { chatView.dispatchTouchEvent(cancel) }
                cancel.recycle()
            }
            VoiceHandle.register(original)
        }
    }
}
