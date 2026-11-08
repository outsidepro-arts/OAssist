package ru.outsideproarts.oassist.feature.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.ImagePart

/**
 * Trampoline для assist-жеста: принимает AssistStructure или memory handle из VIS,
 * запрашивает спецдоступ «поверх окон» и микрофон, поднимает оверлей-сервис и закрывается —
 * пользователь остаётся в своём приложении.
 */
class AssistActivity : AppCompatActivity() {

    private var overlayNeeded = true
    private lateinit var retained: AssistContextViewModel

    private val contextAvailable: Boolean
        get() = retained.initialized && retained.captureToken?.let(AssistSessionStore::isScreenCapturePending) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retained = ViewModelProvider(this)[AssistContextViewModel::class.java]
        if (savedInstanceState != null) {
            if (!contextAvailable) {
                finish()
                return
            }
            overlayNeeded = retained.overlayNeeded
            return
        }
        val incomingToken = intent?.getStringExtra(AssistScreenHandoff.EXTRA_TOKEN)
        if (intent?.hasExtra(AssistScreenHandoff.EXTRA_TOKEN) == true &&
            (incomingToken == null || !AssistSessionStore.isScreenCapturePending(incomingToken))
        ) {
            finish()
            return
        }
        val owner = if (incomingToken == null) AssistSessionStore.beginScreenCapture() else null
        retained.captureToken = incomingToken
        // Пиксели и структура извлекаются до очистки intent; далее остаётся только memory handle.
        val extractedContext = if (incomingToken == null) {
            extractScreenContext(intent)
                ?: intent?.getStringExtra(AssistantOverlayService.EXTRA_SCREEN_CONTEXT)
        } else null
        val extractedImage = if (incomingToken == null) {
            intent?.getStringExtra(AssistantOverlayService.EXTRA_SCREEN_IMAGE)
                ?.let { encoded -> ImagePart(imageBase64 = encoded) }
                ?: extractScreenImage(intent)
        } else null
        // Retain only the memory handle; neither recreation nor the next Binder hop carries pixels.
        intent?.removeExtra(AssistantOverlayService.EXTRA_SCREEN_CONTEXT)
        intent?.removeExtra(AssistantOverlayService.EXTRA_SCREEN_IMAGE)
        intent?.removeExtra("android.app.assist.AssistStructure")
        intent?.removeExtra("android.intent.extra.ASSIST_SCREENSHOT")
        // Конфиг читается асинхронно: runBlocking здесь блокировал главный поток трамплина.
        // При ошибке чтения сохранены прежние умолчания: showOverlay -> true,
        // attachScreenshot -> false (снимок не публикуется). CancellationException не глотается.
        // Осознанно: уничтожение активити во время чтения прерывает трамплин (окно — одно
        // чтение конфига); незавершённый владелец захвата закрывается при отмене ниже,
        // опубликованный токен — в onCleared.
        lifecycleScope.launch {
            val config = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                        .modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                owner?.let(AssistSessionStore::cancelScreenCapture)
                throw cancelled
            } catch (e: Exception) {
                null
            }
            if (isFinishing || isDestroyed) {
                owner?.let(AssistSessionStore::cancelScreenCapture)
                return@launch
            }
            overlayNeeded = AssistSessionStore.activeShowOverlay()
                ?: (config?.assistant?.showOverlay ?: true)
            val attachScreenshot = config != null && (config.assistant?.attachScreenshot ?: true)
            retained.captureToken = incomingToken ?: owner?.let {
                AssistScreenHandoff.publish(it, extractedContext, extractedImage.takeIf { attachScreenshot })
            }
            retained.overlayNeeded = overlayNeeded
            retained.initialized = true
            if (!contextAvailable) {
                finish()
                return@launch
            }

            if (overlayNeeded && !Settings.canDrawOverlays(this@AssistActivity)) {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri(),
                )
                overlayLauncher.launch(overlayIntent)
                return@launch
            }
            if (!overlayNeeded && !notificationsEnabled()) {
                // Notification-режим без уведомлений = «призрачная сессия» без UI: не стартуем.
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
                return@launch
            }
            ensureMicAndStart()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!contextAvailable) {
            finish()
        } else if (!overlayNeeded || Settings.canDrawOverlays(this)) {
            ensureMicAndStart()
        } else {
            Toast.makeText(this, R.string.assistant_no_overlay, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Отказ не блокирует окно: текстовый ввод остаётся, сервис покажет подсказку.
        if (contextAvailable) startServiceAndStartOverlay() else finish()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when {
            !contextAvailable -> finish()
            notificationsEnabled() -> ensureMicAndStart()
            // Выдали, но уведомления выключены в настройках приложения — отправляем туда.
            granted -> openNotificationSettings()
            else -> {
                Toast.makeText(this, R.string.assistant_notifications_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true

    private fun openNotificationSettings() {
        Toast.makeText(this, R.string.assistant_notifications_required, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
        finish()
    }

    private fun ensureMicAndStart() {
        if (!contextAvailable) {
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startServiceAndStartOverlay()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startServiceAndStartOverlay() {
        if (!contextAvailable) {
            finish()
            return
        }
        val serviceIntent = Intent(this, AssistantOverlayService::class.java)
            .putExtra(AssistScreenHandoff.EXTRA_TOKEN, retained.captureToken)
        if (runCatching { startForegroundService(serviceIntent) }.isSuccess) {
            retained.handedOff = true
        }
        finish()
    }

    private fun extractScreenImage(intent: Intent?): ImagePart? {
        val bitmap = runCatching {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Bitmap>("android.intent.extra.ASSIST_SCREENSHOT")
        }.getOrNull() ?: return null
        return runCatching {
            ru.outsideproarts.oassist.core.provider.ImageCodec.fromBitmap(bitmap)
        }.getOrNull()
    }

    private fun extractScreenContext(intent: Intent?): String? {
        return try {
            val assistStructure = intent?.getParcelableExtra<AssistStructure>(
                "android.app.assist.AssistStructure",
            )
            assistStructure?.let { structure ->
                val sb = StringBuilder()
                for (i in 0 until structure.windowNodeCount) {
                    val window = structure.getWindowNodeAt(i)
                    dumpNode(window.rootViewNode, sb, 0)
                }
                sb.toString().take(2000).ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun dumpNode(node: AssistStructure.ViewNode, sb: StringBuilder, depth: Int) {
        if (depth > 5) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotBlank() || desc.isNotBlank()) {
            sb.appendLine("${"  ".repeat(depth)}${if (text.isNotBlank()) text else desc}")
        }
        for (i in 0 until node.childCount) {
            dumpNode(node.getChildAt(i), sb, depth + 1)
        }
    }

}

/** Retains assist data across configuration changes without persisting screenshots. */
class AssistContextViewModel : ViewModel() {
    var initialized = false
    var captureToken: String? = null
    var handedOff = false
    var overlayNeeded = true

    override fun onCleared() {
        if (!handedOff) captureToken?.let(AssistSessionStore::discardScreenCapture)
    }
}
