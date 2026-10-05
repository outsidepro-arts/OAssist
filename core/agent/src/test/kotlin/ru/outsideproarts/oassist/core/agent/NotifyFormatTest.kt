package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyFormatTest {

    @Test
    fun format_emptyList() {
        assertEquals("Активных уведомлений нет", formatNotifications(emptyList()))
    }

    @Test
    fun format_compactRows() {
        val items = listOf(
            NotifyItem(
                key = "0|com.example|1001|null|10001",
                packageName = "com.example",
                title = "Новое сообщение",
                text = "Привет!",
                time = 1_700_000_000_000L,
            ),
            NotifyItem(
                key = "0|com.other|7|null|10002",
                packageName = "com.other",
                title = null,
                text = null,
                time = 1_700_000_060_000L,
            ),
        )
        val lines = formatNotifications(items).lines()
        assertEquals(2, lines.size)
        assertEquals(
            "[0|com.example|1001|null|10001] com.example: Новое сообщение — Привет! (${timeOf(1_700_000_000_000L)})",
            lines[0],
        )
        assertEquals(
            "[0|com.other|7|null|10002] com.other: (без заголовка) (${timeOf(1_700_000_060_000L)})",
            lines[1],
        )
    }

    private fun timeOf(millis: Long): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return f.format(java.util.Date(millis))
    }
}
