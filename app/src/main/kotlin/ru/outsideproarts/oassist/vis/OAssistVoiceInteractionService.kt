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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService
import ru.outsideproarts.oassist.feature.assistant.AssistScreenHandoff
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.agent.isMostlyBlack
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.di.AppGraph

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val capture = AssistCapture<ImagePart>(::deliver)
    private val deadline = Runnable { onDeadline() }
    private var shown = false
    private var closed = false
    private var owner: AssistSessionStore.ScreenCaptureOwner? = null
    private var handoffToken: String? = null
    private var fallbackJob: Job? = null
    private val coordinator = AssistScreenshotCoordinator { image, note ->
        screenshotNote = note
        capture.screenshot(image)
    }
    private var screenshotNote: String? = null

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
        handler.postDelayed(deadline, SHOW_DEADLINE_MS)
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
        // hasPendingFallback — второго колбэка снимка за показ не бывает: дедлайн продлевается один раз.
        if (!capture.acceptsScreenshot || coordinator.hasPendingFallback) return
        val image = screenshot?.let { runCatching { ImageCodec.fromBitmap(it) }.getOrNull() }
        if (screenshot == null || image == null) {
            capture.screenshot(null)
            return
        }
        if (!isMostlyBlack(screenshot)) {
            capture.screenshot(image)
            return
        }
        startFallbackCapture(image)
    }

    /**
     * Системный кадр почти чёрный (шторка скринридера или тёмное содержимое) — пробуем
     * переснять через наш сервис доступности: оконный захват (API 34+) шторку не содержит,
     * скринридер не трогается. Дедлайн сессии продлевается один раз; запоздавший результат
     * координатор отбросит сам.
     */
    private fun startFallbackCapture(blackImage: ImagePart) {
        if (!coordinator.holdForFallback(blackImage)) return
        handler.removeCallbacks(deadline)
        handler.postDelayed(deadline, FALLBACK_EXTRA_MS)
        fallbackJob = scope.launch {
            val quick = try {
                AppGraph.get().uiActions.quickShot()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            coordinator.onFallbackResult(quick)
        }
    }

    private fun onDeadline() {
        coordinator.onDeadline()
        capture.timeout()
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
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelCapture() {
        closed = true
        handler.removeCallbacks(deadline)
        fallbackJob?.cancel()
        fallbackJob = null
        capture.cancel()
        owner?.let(AssistSessionStore::cancelScreenCapture)
    }

    private fun deliver(screenContext: String?, image: ImagePart?) {
        closed = true
        handler.removeCallbacks(deadline)
        val contextWithNote = screenshotNote
            ?.let { note -> listOfNotNull(screenContext, note).joinToString("\n") }
            ?: screenContext
        try {
            val token = owner?.let { AssistScreenHandoff.publish(it, contextWithNote, image) } ?: return
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

    private companion object {
        /** Дедлайн сбора assist-данных: системный снимок и контекст приходят быстро. */
        const val SHOW_DEADLINE_MS = 1_500L

        /** Однократное продление дедлайна под фолбэк-снимок (общий потолок ~3 с от показа). */
        const val FALLBACK_EXTRA_MS = 1_500L
    }
}
