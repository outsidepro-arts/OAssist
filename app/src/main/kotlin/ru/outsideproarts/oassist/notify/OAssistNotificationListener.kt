package ru.outsideproarts.oassist.notify

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Пассивный слушатель уведомлений: список читается только по запросу
 * инструментов ([NotifyActions] через [instance]). Содержимое уведомлений
 * в модель не стримится.
 */
class OAssistNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        // Контракт API: requestRebind — единственный безопасный вызов в этом состоянии.
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, OAssistNotificationListener::class.java),
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Пассивный режим: события игнорируются.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Пассивный режим: события игнорируются.
    }

    companion object {
        @Volatile
        var instance: OAssistNotificationListener? = null
            private set

        /** Выдан ли доступ к уведомлениям в системных настройках. */
        fun isEnabled(context: Context): Boolean {
            val flat = ComponentName(context, OAssistNotificationListener::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
            return ru.outsideproarts.oassist.core.agent.enabledServicesContain(enabled, flat)
        }
    }
}
