package ru.outsideproarts.oassist.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Пассивный сервис доступности: дерево интерфейса читается только по запросу
 * инструмента ([UiActions] через [instance]). Никаких потоков событий в модель,
 * никакого accessibility-feedback — мирно соседствует с TalkBack.
 */
class OAssistAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Пассивный режим: события игнорируются, модель их не видит.
    }

    override fun onInterrupt() {
        // Нечего прерывать: жесты и речь не используются.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: OAssistAccessibilityService? = null
            private set

        /** Включён ли наш сервис в системных настройках доступности. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = ComponentName(context, OAssistAccessibilityService::class.java)
                .flattenToShortString()
            return enabled.split(':').any { it == component }
        }
    }
}
