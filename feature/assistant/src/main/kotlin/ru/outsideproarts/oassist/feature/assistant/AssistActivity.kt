package ru.outsideproarts.oassist.feature.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.AgentToolEvent
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

class AssistActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val agentFactory = ru.outsideproarts.oassist.core.agent.AgentFactory.get()
        val screenContext = extractScreenContext(intent)
        val speech = SpeechHandle.get()

        setContent {
            OAssistTheme {
                AssistOverlay(
                    screenContext = screenContext,
                    onAskAgent = { question ->
                        val assistConfig = agentFactory.modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)
                        val profile = agentFactory.providerForMode(ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)
                            ?: throw ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.PROVIDER_MISSING)
                        val modelId = assistConfig.modelId?.takeIf { it.isNotBlank() }
                            ?: throw ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.MODEL_MISSING)

                        val agent = agentFactory.agentFor(profile, ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)

                        ru.outsideproarts.oassist.core.agent.AgentSession(
                            chunks = agent.stream(
                                modelId = modelId,
                                systemPrompt = agentFactory.systemPromptFor(
                                    ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT,
                                    screenContext = screenContext,
                                ),
                                history = emptyList(),
                                userMessage = question,
                            ),
                            agent = agent,
                        )
                    },
                    // Автоозвучка — из настроек режима ASSISTANT (по умолчанию включена).
                    onClose = {
                        speech?.stop()
                        finish()
                    },
                )
            }
        }
    }

    private fun extractScreenContext(intent: Intent?): String? {
        return try {
            val assistStructure = intent?.getParcelableExtra<android.app.assist.AssistStructure>(
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

    private fun dumpNode(node: android.app.assist.AssistStructure.ViewNode, sb: StringBuilder, depth: Int) {
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

@Composable
private fun AssistOverlay(
    screenContext: String?,
    onAskAgent: suspend (String) -> ru.outsideproarts.oassist.core.agent.AgentSession,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.assistant_title)
    val askLabel = stringResource(R.string.assistant_ask)
    val responseLabel = stringResource(R.string.assistant_response)
    val closeLabel = stringResource(R.string.assistant_close)
    val contextLabel = stringResource(R.string.assistant_context)
    val speakLabel = stringResource(R.string.assistant_speak_answer)
    val context = androidx.compose.ui.platform.LocalContext.current
    val micLabel = stringResource(R.string.assistant_mic)
    val noRecognizerText = stringResource(R.string.assistant_no_recognizer)
    val micDeniedText = stringResource(R.string.assistant_mic_denied)
    val micEmptyText = stringResource(R.string.assistant_mic_empty)
    val micErrorText = stringResource(R.string.assistant_mic_error)
    val listeningText = stringResource(R.string.assistant_listening)

    var question by remember { mutableStateOf("") }
    // Ответ — три раздельные проекции: мысль, вызовы, основной текст.
    var thought by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    val toolEvents = remember { mutableStateListOf<AgentToolEvent>() }
    var sttPreview by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Голосовой ввод — тот же in-app захват, что в чате. Распознанное НЕ пишем
    // в поле: финал уходит сразу в ask(). Порядок: ask → слушатель → onMicClick.
    val voice = remember { VoiceHandle.get() }
    var capturing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(voice) {
        voice?.captureState()?.collect { capturing = it is CaptureState.Listening }
    }
    // Язык — из SttConfig режима (обновляется в ask() при чтении конфига).
    var assistLanguage by remember { mutableStateOf("ru-RU") }
    fun ask(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isStreaming) return
        isStreaming = true
        // Barge-in: новый вопрос прерывает озвучку предыдущего ответа.
        SpeechHandle.get()?.stop()
        val thoughtSb = StringBuilder()
        val answerSb = StringBuilder()
        thought = ""
        answer = ""
        toolEvents.clear()
        sttPreview = ""
        scope.launch {
            val modeTts = runCatching {
                ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                    .modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.ASSISTANT)
            }.getOrNull()
            val autoSpeak = modeTts?.assistant?.autoSpeak ?: true
            val tts = modeTts?.effectiveTts()
                ?: ru.outsideproarts.oassist.core.model.TtsConfig.DEFAULT
            assistLanguage = modeTts?.effectiveStt()?.language ?: "ru-RU"
            val speech = SpeechHandle.get()
            // Озвучка режима ASSISTANT применяется перед каждым стримом.
            speech?.applyTts(tts)
            speech?.setNarrativeEnabled(tts.narrative)
            val thoughtSpeaker = StreamSpeaker(
                speak = { if (autoSpeak) speech?.speak(it) },
                speakToolCalls = tts.speakToolCalls,
            )
            val answerSpeaker = StreamSpeaker(
                speak = { if (autoSpeak) speech?.speak(it) },
                speakToolCalls = true,
            )
            val session = onAskAgent(trimmed)
            val error = session.chunks.collectSafe { chunk ->
                when (chunk) {
                    is ChatChunk.Content -> {
                        if (chunk.isToolComment) {
                            thoughtSb.append(chunk.delta)
                            thought = thoughtSb.toString()
                            thoughtSpeaker.onText(thoughtSb.toString(), true)
                        } else {
                            answerSb.append(chunk.delta)
                            answer = answerSb.toString()
                            answerSpeaker.onText(answerSb.toString(), false)
                        }
                    }
                    is ChatChunk.ToolCall -> {
                        toolEvents.add(AgentToolEvent(name = chunk.name))
                    }
                    is ChatChunk.ToolResult -> {
                        val pending = toolEvents.indexOfLast {
                            it.name == chunk.name && it.output == null
                        }
                        val event = AgentToolEvent(
                            name = chunk.name,
                            output = chunk.output,
                            isError = chunk.isError,
                        )
                        if (pending >= 0) toolEvents[pending] = event
                        else toolEvents.add(event)
                    }
                    // В оверлее нет диалога: опасные действия отклоняем сразу.
                    is ChatChunk.ApprovalNeeded -> {
                        session.agent.resolveApproval(
                            chunk.request.callId,
                            ru.outsideproarts.oassist.core.model.ApprovalDecision.DENY,
                        )
                    }
                    is ChatChunk.Finish -> {
                        thoughtSpeaker.onFinish(thoughtSb.toString())
                        answerSpeaker.onFinish(answerSb.toString())
                        isStreaming = false
                    }
                }
            }
            isStreaming = false
            if (error != null) {
                android.widget.Toast.makeText(
                    context,
                    error.userMessage(context),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    fun sttListener(): SttListener = object : SttListener {
        override fun onPartial(text: String) {
            sttPreview = text
        }

        override fun onFinal(text: String) {
            sttPreview = ""
            if (text.isNotBlank()) {
                question = ""
                ask(text)
            }
        }

        override fun onSttError(error: SttError) {
            sttPreview = ""
            android.widget.Toast.makeText(
                context,
                when (error) {
                    SttError.NO_SPEECH -> micEmptyText
                    SttError.NETWORK,
                    SttError.UNKNOWN -> micErrorText
                },
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun sttErrorToast(error: CaptureError) {
        sttPreview = ""
        android.widget.Toast.makeText(
            context,
            when (error) {
                CaptureError.NO_RECOGNIZER -> noRecognizerText
                CaptureError.NO_PERMISSION -> micDeniedText
                CaptureError.BUSY,
                CaptureError.INIT_FAILED -> micErrorText
            },
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice?.takeIf { it.isSttAvailable() }?.startStt(assistLanguage, sttListener())
                ?.let { sttErrorToast(it) }
                ?: voice?.let { sttErrorToast(CaptureError.NO_RECOGNIZER) }
        } else {
            android.widget.Toast.makeText(context, micDeniedText, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun onMicClick() {
        val handle = voice ?: return
        // Barge-in: тап микрофона прерывает озвучку предыдущего ответа.
        SpeechHandle.get()?.stop()
        if (isStreaming) return
        when (handle.captureState().value) {
            is CaptureState.Listening -> handle.stopStt()
            else -> {
                if (!handle.isSttAvailable()) {
                    sttErrorToast(CaptureError.NO_RECOGNIZER)
                    return
                }
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    handle.startStt(assistLanguage, sttListener())?.let { sttErrorToast(it) }
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(OAssistDimens.ScreenContentPadding),
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.oassistHeading(),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = closeLabel,
                )
            }
        }

        if (screenContext != null) {
            OAssistPanel(title = contextLabel, showTitle = true) {
                Text(
                    text = screenContext.take(500),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text(askLabel) },
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            IconButton(
                onClick = { onMicClick() },
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Icon(
                    imageVector = if (capturing) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = micLabel,
                )
            }
            IconButton(
                onClick = {
                    if (question.isNotBlank() && !isStreaming) {
                        val text = question.trim()
                        question = ""
                        sttPreview = ""
                        ask(text)
                    }
                },
                enabled = question.isNotBlank() && !isStreaming,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = askLabel,
                )
            }
        }

        if (capturing) {
            val status = if (sttPreview.isBlank()) listeningText else "$listeningText $sttPreview"
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (answer.isNotEmpty() || thought.isNotBlank() || toolEvents.isNotEmpty()) {
            OAssistPanel(title = responseLabel, showTitle = true) {
                if (thought.isNotBlank() || toolEvents.isNotEmpty()) {
                    AssistWorkBlock(thought = thought, tools = toolEvents.toList())
                }
                // Видимый текст — НЕ live region (читалась бы каждая дельта целиком).
                // Озвучка — через канал речи; повтор — действием «Озвучить ответ».
                Text(
                    text = answer.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(speakLabel) {
                                val speech = SpeechHandle.get() ?: return@CustomAccessibilityAction false
                                SentenceSplitter.splitSentences(answer).forEach { speech.speak(it) }
                                true
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AssistWorkBlock(thought: String, tools: List<AgentToolEvent>) {
    val title = stringResource(R.string.assistant_work_title)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) {
        stringResource(R.string.assistant_work_expanded)
    } else {
        stringResource(R.string.assistant_work_collapsed)
    }
    val actionLabel = if (expanded) {
        stringResource(R.string.assistant_action_collapse)
    } else {
        stringResource(R.string.assistant_action_expand)
    }
    OAssistPanel(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = actionLabel },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            if (thought.isNotBlank()) {
                Text(
                    text = thought.take(2000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tools.takeLast(20).forEach { tool ->
                Text(
                    text = tool.name + (tool.output?.take(500)?.let { ": $it" } ?: "…"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
