package ru.outsideproarts.oassist

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.a11y.OAssistAccessibilityService
import ru.outsideproarts.oassist.notify.OAssistNotificationListener
import ru.outsideproarts.oassist.vis.OAssistRecognitionService
import ru.outsideproarts.oassist.vis.OAssistVoiceInteractionService
import ru.outsideproarts.oassist.vis.OAssistVoiceInteractionSessionService

/**
 * Дымовой тест VIS-стека (M5.4). Только устройство: проверяет регистрацию
 * сервисов в манифесте и читаемость их включённости. На JVM не выполняется.
 */
@RunWith(AndroidJUnit4::class)
class VisStackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm: PackageManager = context.packageManager

    @Test
    fun voiceInteractionService_declared() {
        val intent = Intent("android.service.voice.VoiceInteractionService").apply {
            `package` = context.packageName
        }
        val services = pm.queryIntentServices(intent, 0)
        assertTrue(
            services.any { it.serviceInfo.name.endsWith("OAssistVoiceInteractionService") },
        )
    }

    @Test
    fun recognitionService_declared() {
        val cn = ComponentName(context, OAssistRecognitionService::class.java)
        val info = pm.getServiceInfo(cn, 0)
        assertNotNull(info)
        // BIND_RECOGNITION_SERVICE — скрытая константа SDK, сверяем строкой.
        assertTrue(info.permission == "android.permission.BIND_RECOGNITION_SERVICE")
    }

    @Test
    fun sessionService_declared() {
        val cn = ComponentName(context, OAssistVoiceInteractionSessionService::class.java)
        assertNotNull(pm.getServiceInfo(cn, 0))
    }

    @Test
    fun accessibilityService_declared() {
        val cn = ComponentName(context, OAssistAccessibilityService::class.java)
        val info = pm.getServiceInfo(cn, 0)
        assertNotNull(info)
        assertTrue(info.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE)
    }

    @Test
    fun notificationListener_declared() {
        val cn = ComponentName(context, OAssistNotificationListener::class.java)
        val info = pm.getServiceInfo(cn, 0)
        assertNotNull(info)
        assertTrue(info.permission == android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)
    }

    @Test
    fun shizukuProvider_declared() {
        val authority = "${context.packageName}.shizuku"
        assertNotNull(pm.resolveContentProvider(authority, 0))
    }

    @Test
    fun taskService_isSpecialUse() {
        val cn = ComponentName(context, "ru.outsideproarts.oassist.tasks.OAssistTaskService")
        val info = pm.getServiceInfo(cn, PackageManager.GET_META_DATA)
        assertNotNull(info)
        assertTrue((info.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0)
    }

    @Test
    fun statusQueries_doNotCrash() {
        // Чтение включённости не должно падать ни в каком состоянии системы.
        OAssistAccessibilityService.isEnabled(context)
        // ENABLED_NOTIFICATION_LISTENERS — скрытая константа, сверяем строкой.
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    }
}
