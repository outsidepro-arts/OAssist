package ru.outsideproarts.oassist.core.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityInvariantsTest {

    @Test
    fun `минимальный touch target 48dp`() {
        assertEquals(48, AccessibilityInvariants.MIN_TOUCH_TARGET_DP)
        assertEquals(AccessibilityInvariants.MIN_TOUCH_TARGET_DP, A11y.MIN_TOUCH_TARGET)
    }

    @Test
    fun `инварианты контраста соответствуют AA`() {
        assertEquals(4.5f, AccessibilityInvariants.MIN_CONTRAST_TEXT, 0.001f)
        assertEquals(3.0f, AccessibilityInvariants.MIN_CONTRAST_UI, 0.001f)
    }

    @Test
    fun `у сообщений чата обязательный набор действий`() {
        val required = AccessibilityInvariants.MESSAGE_ACTIONS_REQUIRED
        assertTrue(required.contains("copy"))
        assertTrue(required.contains("delete"))
        assertTrue(required.contains("speak"))
    }

    @Test
    fun `у событий timeline есть действия подробностей и повтора`() {
        val required = AccessibilityInvariants.TIMELINE_ACTIONS_REQUIRED
        assertTrue(required.contains("details"))
        assertTrue(required.contains("retry"))
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
