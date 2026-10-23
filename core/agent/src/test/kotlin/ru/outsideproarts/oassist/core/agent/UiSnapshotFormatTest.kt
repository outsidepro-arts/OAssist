package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSnapshotFormatTest {

    @Test
    fun format_emptySnapshot() {
        assertEquals("Экран пуст или недоступен", formatSnapshot(emptyList()))
    }

    @Test
    fun format_compactRows() {
        val nodes = listOf(
            UiNode(
                index = 0,
                className = "android.widget.Button",
                text = "Отправить",
                contentDescription = null,
                clickable = true,
                editable = false,
                scrollable = false,
                bounds = UiRect(100, 200, 300, 250),
            ),
            UiNode(
                index = 1,
                className = "android.widget.EditText",
                text = "",
                contentDescription = "Поиск",
                clickable = false,
                editable = true,
                scrollable = false,
                bounds = UiRect(0, 0, 1080, 150),
            ),
            UiNode(
                index = 2,
                className = "androidx.recyclerview.widget.RecyclerView",
                text = null,
                contentDescription = null,
                clickable = false,
                editable = false,
                scrollable = true,
                bounds = UiRect(0, 150, 1080, 1920),
            ),
        )
        val lines = formatSnapshot(nodes).lines()
        assertEquals(3, lines.size)
        assertEquals("[0] Button «Отправить» (clickable) [100,200-300,250]", lines[0])
        assertEquals("[1] EditText «Поиск» (editable) [0,0-1080,150]", lines[1])
        assertEquals("[2] RecyclerView (scrollable) [0,150-1080,1920]", lines[2])
    }

    @Test
    fun label_prefersTextOverDescription() {
        val node = UiNode(0, "View", "Текст", "Описание", false, false, false, UiRect(0, 0, 1, 1))
        assertEquals("Текст", node.label)
        val descOnly = node.copy(text = "  ", contentDescription = "Описание")
        assertEquals("Описание", descOnly.label)
        val empty = node.copy(text = null, contentDescription = null)
        assertNull(empty.label)
    }

    @Test
    fun rect_centerCoordinates() {
        val rect = UiRect(100, 200, 300, 400)
        assertEquals(200, rect.centerX)
        assertEquals(300, rect.centerY)
    }

    @Test
    fun format_truncationMarker_whenTotalExceedsShown() {
        val nodes = listOf(
            UiNode(0, "View", "x", null, true, false, false, UiRect(0, 0, 10, 10)),
        )
        val text = formatSnapshot(nodes, total = 42)
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("показано 1 из 42"))
        assertTrue(lines[1].startsWith("[0]"))
    }

    @Test
    fun format_header_screenAndOwnWindow() {
        val nodes = listOf(
            UiNode(0, "View", "x", null, true, false, false, UiRect(0, 0, 10, 10)),
        )
        val text = formatSnapshot(nodes, total = 1, screenHeight = 2400, fromOwnWindow = true)
        val header = text.lines()[0]
        assertTrue(header.contains("0..2400"))
        assertTrue(header.contains("OAssist"))
    }

    @Test
    fun format_noHeader_withoutFlags() {
        val nodes = listOf(
            UiNode(0, "View", "x", null, true, false, false, UiRect(0, 0, 10, 10)),
        )
        val text = formatSnapshot(nodes)
        assertEquals(1, text.lines().size)
    }

    @Test
    fun format_hasActionsFlag_afterBaseFlags() {
        val nodes = listOf(
            UiNode(0, "TextView", "Привет", null, true, false, false, UiRect(0, 0, 10, 10), hasActions = true),
            UiNode(1, "View", "x", null, false, false, false, UiRect(0, 0, 10, 10), hasActions = true),
        )
        val lines = formatSnapshot(nodes).lines()
        assertEquals("[0] TextView «Привет» (clickable,has_actions) [0,0-10,10]", lines[0])
        assertEquals("[1] View «x» (has_actions) [0,0-10,10]", lines[1])
    }

    @Test
    fun format_noActionsFlag_withoutHiddenActions() {
        val nodes = listOf(
            UiNode(0, "View", "x", null, true, false, false, UiRect(0, 0, 10, 10)),
        )
        assertFalse(formatSnapshot(nodes).contains("has_actions"))
    }
}
