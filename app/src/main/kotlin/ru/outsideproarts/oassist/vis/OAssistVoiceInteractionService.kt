package ru.outsideproarts.oassist.vis

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.model.AssistMode

/**
 * VoiceInteractionSession: принимает assist-данные (AssistStructure + скриншот),
 * форвардит в AgentFactory и показывает простой UI с ответом.
 */
class OAssistVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OAssistVoiceInteractionSession(this)
    }
}

class OAssistVoiceInteractionSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resultTextView: TextView? = null

    /** Озвучка сессии: конфиг ASSISTANT применяется перед каждым стримом. */
    private fun speech() = ru.outsideproarts.oassist.di.AppGraph.get().speechOutput

    override fun onCreateContentView(): View {
        val scrollView = ScrollView(context)
        val container = FrameLayout(context).apply {
            setPadding(48, 96, 48, 48)
        }
        val textView = TextView(context).apply {
            textSize = 16f
            setPadding(24, 24, 24, 24)
            text = "OAssist: думаю..."
        }
        resultTextView = textView
        container.addView(textView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        scrollView.addView(container)
        return scrollView
    }

    override fun onHide() {
        super.onHide()
        // Скрытие панели гасит озвучку (barge-in наоборот: UI ушёл — звук тоже).
        runCatching { speech().stop() }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val structure = try {
            state::class.java.getMethod("getStructure").invoke(state) as? AssistStructure
        } catch (e: Exception) {
            null
        }
        val screenContext = extractScreenContext(structure)

        val factory = ru.outsideproarts.oassist.core.agent.AgentFactory.get()

        // Barge-in: новый жест поверх говорящего прерывает озвучку.
        runCatching { speech().stop() }
        scope.launch {
            try {
                val config = factory.modeConfig(AssistMode.ASSISTANT)
                val profile = factory.providerForMode(AssistMode.ASSISTANT)
                if (profile != null) {
                    val agent = factory.agentFor(profile, AssistMode.ASSISTANT)
                    val tts = config.effectiveTts()
                    val autoSpeak = config.assistant?.autoSpeak ?: true
                    val graph = ru.outsideproarts.oassist.di.AppGraph.get()
                    graph.applyModeTts(tts)
                    // В окне сессии нет AnnouncerHost: SCREEN_READER озвучиваем
                    // одним объявлением готового ответа на Finish (см. ниже),
                    // стрим-анонсы сюда не дойдут — их не будет вовсе, не дубль.
                    val screenReader = tts.channel ==
                        ru.outsideproarts.oassist.core.model.SpeechOutputChannel.SCREEN_READER
                    val speaker = ru.outsideproarts.oassist.core.speech.StreamSpeaker(
                        speak = { if (autoSpeak && !screenReader) speech().speak(it) },
                        speakToolCalls = tts.speakToolCalls,
                    )

                    val sb = StringBuilder()
                    agent.stream(
                        modelId = config.modelId ?: "",
                        systemPrompt = factory.systemPromptFor(
                            AssistMode.ASSISTANT,
                            screenContext = screenContext,
                        ),
                        history = emptyList(),
                        userMessage = "Что на экране? Помоги пользователю.",
                    ).collect { chunk ->
                        when (chunk) {
                            is ru.outsideproarts.oassist.core.provider.ChatChunk.Content -> {
                                sb.append(chunk.delta)
                                resultTextView?.text = sb.toString()
                                speaker.onText(sb.toString(), chunk.isToolComment)
                            }
                            // В голосовой сессии нет диалога: опасные действия отклоняем сразу.
                            is ru.outsideproarts.oassist.core.provider.ChatChunk.ApprovalNeeded -> {
                                agent.resolveApproval(
                                    chunk.request.callId,
                                    ru.outsideproarts.oassist.core.model.ApprovalDecision.DENY,
                                )
                            }
                            is ru.outsideproarts.oassist.core.provider.ChatChunk.Finish -> {
                                speaker.onFinish(sb.toString())
                                if (autoSpeak && screenReader && sb.isNotEmpty()) {
                                    resultTextView?.announceForAccessibility(sb.toString())
                                }
                            }
                            else -> {}
                        }
                    }
                } else {
                    resultTextView?.text = "Провайдер не настроен для режима Ассистент."
                }
            } catch (e: Exception) {
                resultTextView?.text = "Ошибка: ${e.message}"
            }
        }
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
