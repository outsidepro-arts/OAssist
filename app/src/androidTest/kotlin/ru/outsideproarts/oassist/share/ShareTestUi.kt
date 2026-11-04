package ru.outsideproarts.oassist.share

import android.view.WindowInsets
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ActivityScenario
import ru.outsideproarts.oassist.feature.share.ShareActivity

internal fun ComposeContentTestRule.dismissShareKeyboard(scenario: ActivityScenario<ShareActivity>) {
    scenario.onActivity { activity ->
        activity.currentFocus?.clearFocus()
        activity.window.insetsController?.hide(WindowInsets.Type.ime())
    }
    waitUntil(10_000) {
        var hidden = false
        scenario.onActivity { activity ->
            val insets = activity.window.decorView.rootWindowInsets
            hidden = insets != null && !insets.isVisible(WindowInsets.Type.ime()) &&
                insets.getInsets(WindowInsets.Type.ime()).bottom == 0
        }
        hidden
    }
    waitForIdle()
}
