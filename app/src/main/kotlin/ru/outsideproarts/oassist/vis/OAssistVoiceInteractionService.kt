package ru.outsideproarts.oassist.vis

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.FrameLayout
import ru.outsideproarts.oassist.feature.assistant.AssistActivity
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService

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

    override fun onCreateContentView(): View = FrameLayout(context)

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val structure = try {
            state::class.java.getMethod("getStructure").invoke(state) as? AssistStructure
        } catch (e: Exception) {
            null
        }
        val screenContext = extractScreenContext(structure)
        val intent = Intent(context, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            screenContext?.let { putExtra(AssistantOverlayService.EXTRA_SCREEN_CONTEXT, it) }
        }
        runCatching { context.startActivity(intent) }
        hide()
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
