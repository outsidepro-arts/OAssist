package ru.outsideproarts.oassist.core.agent

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Одна строка списка уведомлений для модели. */
data class NotifyItem(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val time: Long,
)

/** Компактный список уведомлений для модели. */
fun formatNotifications(items: List<NotifyItem>): String {
    if (items.isEmpty()) return "Активных уведомлений нет"
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    return items.joinToString("\n") { item ->
        val title = item.title?.takeIf { it.isNotBlank() } ?: "(без заголовка)"
        val text = item.text?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        val time = timeFormat.format(Date(item.time))
        "[${item.key}] ${item.packageName}: $title$text ($time)"
    }
}

/**
 * Операции над уведомлениями через пассивный NotificationListenerService.
 * Сервис поставляется приложением ([serviceProvider]); если доступ не выдан —
 * явная ошибка с подсказкой.
 */
class NotifyActions(
    private val serviceProvider: () -> NotificationListenerService?,
) {

    private fun serviceOrError(): Result<NotificationListenerService> {
        val service = serviceProvider()
            ?: return Result.failure(IllegalStateException(SERVICE_DISABLED_MESSAGE))
        return Result.success(service)
    }

    fun list(): ToolResult {
        val service = serviceOrError().getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        return runCatching {
            val items = service.activeNotifications
                ?.take(MAX_ITEMS)
                ?.map { it.toItem() }
                ?: emptyList()
            ToolResult(true, formatNotifications(items))
        }.getOrElse { ToolResult(false, "Не удалось прочитать уведомления: ${it.message}") }
    }

    fun open(key: String): ToolResult {
        val service = serviceOrError().getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        val sbn = service.activeNotifications?.find { it.key == key }
            ?: return ToolResult(false, "Уведомление не найдено — обновите список")
        val intent = sbn.notification.contentIntent
            ?: return ToolResult(false, "У уведомления нет действия открытия")
        return runCatching {
            intent.send()
            ToolResult(true, "Уведомление открыто")
        }.getOrElse { ToolResult(false, "Не удалось открыть уведомление: ${it.message}") }
    }

    fun clear(key: String): ToolResult {
        val service = serviceOrError().getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        return runCatching {
            service.cancelNotification(key)
            ToolResult(true, "Уведомление скрыто")
        }.getOrElse { ToolResult(false, "Не удалось скрыть уведомление: ${it.message}") }
    }

    private fun StatusBarNotification.toItem(): NotifyItem {
        val extras = notification.extras
        return NotifyItem(
            key = key,
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            time = notification.`when`,
        )
    }

    companion object {
        const val SERVICE_DISABLED_MESSAGE =
            "Доступ к уведомлениям не выдан — включите его в системных настройках"
        private const val MAX_ITEMS = 20
    }
}
