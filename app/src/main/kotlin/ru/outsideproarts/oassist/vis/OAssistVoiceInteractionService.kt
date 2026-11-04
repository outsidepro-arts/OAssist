package ru.outsideproarts.oassist.vis

import android.Manifest
import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.FrameLayout
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService
import ru.outsideproarts.oassist.feature.assistant.AssistScreenHandoff
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.ImagePart

class OAssistVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OAssistVoiceInteractionSession(this)
    }
}

/**
 * Тонкий вход: assist-жест извлекает контекст экрана и передаёт управление оверлей-сервису
 * (через trampoline AssistActivity — там же запрос спецдоступов и микрофона).
 * Своего UI сессия не держит: окно ассистента живёт в [AssistantOverlayService].
 */
class OAssistVoiceInteractionSession(
    service: VoiceInteractionSessionService,
) : VoiceInteractionSession(service) {
    private val handler = Handler(Looper.getMainLooper())
    private val capture = AssistCapture<ImagePart>(::deliver)
    private val deadline = Runnable { capture.timeout() }
    private var shown = false
    private var closed = false
    private var owner: AssistSessionStore.ScreenCaptureOwner? = null
    private var handoffToken: String? = null

    override fun onCreateContentView(): View = FrameLayout(context)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Neither assist nor screenshot callbacks carry a show ID. Never reuse this collector.
        if (shown || closed) {
            handoffToken?.let(AssistSessionStore::discardScreenCapture)
            cancelCapture()
            finish()
            return
        }
        shown = true
        owner = AssistSessionStore.beginScreenCapture()
        handler.postDelayed(deadline, 1_500L)
        capture.start(
            expectContext = showFlags and SHOW_WITH_ASSIST != 0,
            expectScreenshot = showFlags and SHOW_WITH_SCREENSHOT != 0,
            screenshotEnabled = {
                kotlinx.coroutines.runBlocking {
                    ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                        .modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)
                        .assistant?.attachScreenshot ?: true
                }
            },
        )
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (state.index == 0 && capture.acceptsContext) {
            capture.context(extractScreenContext(state.assistStructure))
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (capture.acceptsScreenshot) {
            capture.screenshot(screenshot?.let { runCatching { ImageCodec.fromBitmap(it) }.getOrNull() })
        }
    }

    override fun onHide() {
        super.onHide()
        if (!closed) {
            cancelCapture()
            finish()
        }
    }

    override fun onDestroy() {
        cancelCapture()
        super.onDestroy()
    }

    private fun cancelCapture() {
        closed = true
        handler.removeCallbacks(deadline)
        capture.cancel()
        owner?.let(AssistSessionStore::cancelScreenCapture)
    }

    private fun deliver(screenContext: String?, image: ImagePart?) {
        closed = true
        handler.removeCallbacks(deadline)
        try {
            val token = owner?.let { AssistScreenHandoff.publish(it, screenContext, image) } ?: return
            handoffToken = token
            if (!startOverlay(token)) {
                val intent = Intent(context, AssistActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(AssistScreenHandoff.EXTRA_TOKEN, token)
                }
                if (runCatching { context.startActivity(intent) }.isFailure) {
                    AssistSessionStore.discardScreenCapture(token)
                }
            }
        } finally {
            finish()
        }
    }

    /**
     * Прямой старт оверлей-сервиса без activity: на путь влияет трамплин (запрос разрешений,
     * изолированный таск). При выданных разрешениях стартуем сервис напрямую — так таск
     * приложения вообще не трогается; исключение FGS-старта откатывает на трамплин.
     */
    private fun startOverlay(token: String): Boolean {
        val showOverlay = AssistSessionStore.activeShowOverlay() ?: kotlinx.coroutines.runBlocking {
            runCatching {
                ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                    .modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)
                    .assistant?.showOverlay ?: true
            }.getOrDefault(true)
        }
        if (showOverlay && !Settings.canDrawOverlays(context)) return false
        if (!showOverlay) {
            // Notification-режим требует видимых уведомлений — иначе сессия «призрачная»:
            // откатываемся в трамплин AssistActivity, где запрашивается POST_NOTIFICATIONS.
            val notificationsEnabled = runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.areNotificationsEnabled()
            }.getOrNull() ?: true
            if (!notificationsEnabled) return false
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val intent = Intent(context, AssistantOverlayService::class.java).apply {
            putExtra(AssistScreenHandoff.EXTRA_TOKEN, token)
        }
        return runCatching {
            context.startForegroundService(intent)
        }.isSuccess
    }

    private fun extractScreenContext(structure: AssistStructure?): String? {
        if (structure == null) return null
        return try {
            val sb = StringBuilder()
            for (i in 0 until structure.windowNodeCount) {
                val window = structure.getWindowNodeAt(i)
                dumpNode(window.rootViewNode, sb, 0)
            }
            sb.toString().take(2000).ifBlank { null }
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
