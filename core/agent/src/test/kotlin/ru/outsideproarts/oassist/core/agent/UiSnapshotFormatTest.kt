package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
