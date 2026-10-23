package ru.outsideproarts.oassist.core.agent

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-логика скрытых действий: фильтр, перенумерация и вывод для модели.
 * `AccessibilityNodeInfo.ACTION_*` — константы времени компиляции, в JVM-тесты инлайнятся.
 */
class NodeActionsFormatTest {

    @Test
    fun `кастомная подпись всегда скрытое действие`() {
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_CLICK, "Ответить"))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Переслать"))
        assertTrue(isHiddenAction(0x7f0a0123, "Ответить"))
    }

    @Test
    fun `база уже отражённая в снимке не показывается`() {
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_SET_TEXT, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
    }

    @Test
    fun `внутренности TalkBack не показываются`() {
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_FOCUS, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_SELECT, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, null))
        assertFalse(isHiddenAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, null))
    }

    @Test
    fun `длинное нажатие и rich-действия показываются`() {
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_EXPAND, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_DISMISS, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_COPY, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_PASTE, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_CUT, null))
        assertTrue(isHiddenAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, null))
    }

    @Test
    fun `неизвестный id fail-open — показывается`() {
        assertTrue(isHiddenAction(0x0102037c, null))
        assertTrue(isHiddenAction(999999, null))
    }

    @Test
    fun `hiddenActions перенумеровывает с нуля и сохраняет id`() {
        val raw = listOf(
            UiNodeAction(0, AccessibilityNodeInfo.ACTION_CLICK, null),
            UiNodeAction(1, AccessibilityNodeInfo.ACTION_LONG_CLICK, null),
            UiNodeAction(2, AccessibilityNodeInfo.ACTION_FOCUS, null),
            UiNodeAction(3, 4242, "Ответить"),
        )
        val hidden = hiddenActions(raw)
        assertEquals(2, hidden.size)
        assertEquals(0, hidden[0].index)
        assertEquals(AccessibilityNodeInfo.ACTION_LONG_CLICK, hidden[0].id)
        assertEquals(1, hidden[1].index)
        assertEquals(4242, hidden[1].id)
        assertEquals("Ответить", hidden[1].label)
    }

    @Test
    fun `формат списка действий`() {
        val actions = listOf(
            UiNodeAction(0, 4242, "Ответить"),
            UiNodeAction(1, AccessibilityNodeInfo.ACTION_LONG_CLICK, null),
        )
        val text = formatNodeActions(5, "Привет", actions)
        assertEquals(
            "Действия элемента №5 «Привет»:\n[0] «Ответить»\n[1] «long_click»",
            text,
        )
    }

    @Test
    fun `формат без подписи узла — без кавычек названия`() {
        val text = formatNodeActions(2, null, listOf(UiNodeAction(0, AccessibilityNodeInfo.ACTION_EXPAND, null)))
        assertEquals("Действия элемента №2:\n[0] «expand»", text)
    }

    @Test
    fun `пустой список — пояснение, не ошибка`() {
        val text = formatNodeActions(3, "Узел", emptyList())
        assertTrue(text.contains("№3 «Узел»"))
        assertTrue(text.contains("скрытых действий нет"))
        assertTrue(text.contains("ui_click"))
    }

    @Test
    fun `имя действия — подпись в кавычках, иначе каноническое имя`() {
        assertEquals("«Ответить»", actionName(UiNodeAction(0, 99, "Ответить")))
        assertEquals("«long_click»", actionName(UiNodeAction(0, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)))
        assertEquals("«expand»", actionName(UiNodeAction(0, AccessibilityNodeInfo.ACTION_EXPAND, null)))
        assertEquals("«action_77777»", actionName(UiNodeAction(0, 77777, null)))
    }
}
