package ru.outsideproarts.oassist.chat

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
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
class ChatTogglePermissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionGrantStartsToggleOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Use tools/run-system-permission-tests.ps1", "true", InstrumentationRegistry.getArguments().getString("systemPermissionFixture"))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val original = checkNotNull(VoiceHandle.get())
        val voice = ChatToggleNavigationTest.TestVoice()
        VoiceHandle.register(voice)
        try {
            compose.setContent {
                MaterialTheme { ChatScreen(sendMessage = { _, _, _, _, _ -> error("Unexpected send") }) }
            }
            compose.onNode(hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true)).performClick()
            val deadline = System.currentTimeMillis() + 10_000
            var button: AccessibilityNodeInfo? = null
            while (button == null && System.currentTimeMillis() < deadline) {
                button = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_foreground_only_button")
                    ?.firstOrNull()
                if (button == null) Thread.sleep(100)
            }
            assertTrue("Permission grant button not found", button != null)
            assertTrue(checkNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(10_000L) { voice.starts > 0 }
            compose.waitForIdle()
            assertEquals(1, voice.starts)
        } finally {
            VoiceHandle.register(original)
        }
    }
}
