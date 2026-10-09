package ru.outsideproarts.oassist.core.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Активен ли в системе скринридер (аналог TeamTalk `isScreenReaderActive()`):
 * служба доступности с голосовой обратной связью (TalkBack) включена.
 * Канал SCREEN_READER без активного скринридера уходит в пустоту —
 * вызывающий показывает хинт, а не молчит.
 */
fun isScreenReaderEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java)
        ?: return false
    if (!manager.isEnabled) return false
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        .isNotEmpty()
}
