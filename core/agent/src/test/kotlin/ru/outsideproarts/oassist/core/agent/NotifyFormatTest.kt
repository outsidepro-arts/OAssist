package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyFormatTest {

    @Test
    fun format_emptyList() {
        assertEquals("Доступных активных уведомлений других приложений нет (показано 0 из 0)", formatNotifications(emptyList()))
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
        assertEquals(3, lines.size)
        assertEquals("Доступные уведомления других приложений: показано 2 из 2", lines[0])
        assertEquals(
            "[0|com.example|1001|null|10001] com.example: Новое сообщение — Привет! (${timeOf(1_700_000_000_000L)})",
            lines[1],
        )
        assertEquals(
            "[0|com.other|7|null|10002] com.other: (без заголовка) (${timeOf(1_700_000_060_000L)})",
            lines[2],
        )
    }

    @Test
    fun format_reportsPartialNotificationList() {
        val items = (1..20).map { NotifyItem("$it", "com.example", "title", null, 0L) }
        val complete = formatNotifications(items, 20)
        val partial = formatNotifications(items, 21)
        assertTrue(complete.startsWith("Доступные уведомления других приложений: показано 20 из 20\n"))
        assertTrue(partial.startsWith("Доступные уведомления других приложений: показано 20 из 21 (частичный список)\n"))
    }

    private fun timeOf(millis: Long): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return f.format(java.util.Date(millis))
    }
}
