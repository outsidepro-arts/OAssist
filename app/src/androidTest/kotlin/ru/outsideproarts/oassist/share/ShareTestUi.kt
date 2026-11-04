package ru.outsideproarts.oassist.share

import android.view.WindowInsets
import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import ru.outsideproarts.oassist.feature.share.ShareActivity

internal fun ActivityScenario<ShareActivity>.getString(@StringRes resId: Int): String {
    // AppCompat can localize the Activity independently of the instrumentation targetContext.
    lateinit var text: String
    onActivity { text = it.getString(resId) }
    return text
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.dismissShareKeyboard(scenario: ActivityScenario<ShareActivity>) {
    waitForIdle()
    waitUntil(10_000) {
        var ready = false
        scenario.onActivity { ready = it.window.decorView.hasWindowFocus() }
        ready
    }
    waitForIdle()
    if (onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()) {
        // Move Compose focus off the editor without activating another control.
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
    }
    scenario.onActivity { activity ->
        activity.window.insetsController?.hide(WindowInsets.Type.ime())
    }
    waitUntil(10_000) {
        var hidden = false
        scenario.onActivity { activity ->
            val insets = activity.window.decorView.rootWindowInsets
            hidden = insets != null && !insets.isVisible(WindowInsets.Type.ime()) &&
                insets.getInsets(WindowInsets.Type.ime()).bottom == 0
        }
        hidden && onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty()
    }
    waitForIdle()
}
