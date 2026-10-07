package ru.outsideproarts.oassist.core.agent

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWindowFilterTest {

    private val own = "ru.outsideproarts.oassist"

    @Test
    fun ownOverlayWindow_isIgnorable() {
        // TYPE_APPLICATION_OVERLAY мапится в TYPE_UNKNOWN (0) — панель ассистента.
        assertTrue(isIgnorableWindow(own, 0, own))
    }

    @Test
    fun ownActivityWindow_isVisible() {
        assertFalse(isIgnorableWindow(own, AccessibilityWindowInfo.TYPE_APPLICATION, own))
    }

    @Test
    fun inputMethod_isAlwaysIgnorable() {
        assertTrue(isIgnorableWindow("com.android.inputmethod", AccessibilityWindowInfo.TYPE_INPUT_METHOD, own))
    }

    @Test
    fun accessibilityOverlay_isAlwaysIgnorable() {
        assertTrue(isIgnorableWindow("com.google.android.marvin.talkback", AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, own))
    }

    @Test
    fun foreignAppWindow_isVisible() {
        assertFalse(isIgnorableWindow("org.telegram.messenger", AccessibilityWindowInfo.TYPE_APPLICATION, own))
        assertFalse(isIgnorableWindow("org.telegram.messenger", 0, own))
    }

    @Test
    fun activeAppBeatsTopmostStatusBar() {
        // Список окон top-first: статус-бар выше приложения по слою, но целью быть не должен.
        val windows = listOf(
            UiWindowState("com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM, active = false, focused = false),
            UiWindowState("org.telegram.messenger", AccessibilityWindowInfo.TYPE_APPLICATION, active = true, focused = true),
        )

        assertEquals(1, pickWindowIndex(windows, own))
    }

    @Test
    fun appPickedEvenWhenNothingActive() {
        val windows = listOf(
            UiWindowState("com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM, active = false, focused = false),
            UiWindowState("org.telegram.messenger", AccessibilityWindowInfo.TYPE_APPLICATION, active = false, focused = false),
        )

        assertEquals(1, pickWindowIndex(windows, own))
    }

    @Test
    fun ownPillInFocus_appStillPicked() {
        val windows = listOf(
            UiWindowState(own, 0, active = true, focused = true),
            UiWindowState("org.telegram.messenger", AccessibilityWindowInfo.TYPE_APPLICATION, active = false, focused = false),
        )

        assertEquals(1, pickWindowIndex(windows, own))
    }

    @Test
    fun activeNotificationShade_isPicked() {
        val windows = listOf(
            UiWindowState("com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM, active = true, focused = true),
            UiWindowState("org.telegram.messenger", AccessibilityWindowInfo.TYPE_APPLICATION, active = false, focused = false),
        )

        assertEquals(0, pickWindowIndex(windows, own))
    }

    @Test
    fun onlySystemChrome_returnsNullForFallback() {
        val windows = listOf(
            UiWindowState("com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM, active = false, focused = false),
        )

        assertEquals(null, pickWindowIndex(windows, own))
    }
}
