package ru.outsideproarts.oassist.share

import android.view.WindowInsets
import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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

/**
 * Имя кнопки действия в composer: единая кнопка (Mic / Send) объявляет текущее
 * состояние — при пустом поле это подсказка диктовки, при тексте — «Отправить».
 * Геометрические проверки измеряют одну и ту же кнопку в её текущем состоянии.
 */
internal fun ComposeContentTestRule.composeActionLabel(scenario: ActivityScenario<ShareActivity>): String {
    val send = scenario.getString(ru.outsideproarts.oassist.feature.share.R.string.share_send)
    if (onAllNodesWithContentDescription(send).fetchSemanticsNodes().isNotEmpty()) return send
    val mic = scenario.getString(ru.outsideproarts.oassist.feature.share.R.string.share_mic)
    val hold = scenario.getString(ru.outsideproarts.oassist.feature.share.R.string.share_hold_hint)
    return "$mic. $hold"
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
