package ru.outsideproarts.oassist.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

private const val SHARE_MAX_IMAGES = 4

class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = SharePayload.from(intent)
        val agentFactory = ru.outsideproarts.oassist.core.agent.AgentFactory.get()

        setContent {
            OAssistTheme {
                ShareScreen(
                    payload = payload,
                    onAskAgent = { question ->
                        val shareConfig = agentFactory.modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.SHARE)
                        val profile = agentFactory.providerForMode(ru.outsideproarts.oassist.core.model.AssistMode.SHARE)
                            ?: throw ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.PROVIDER_MISSING)
                        val modelId = shareConfig.modelId?.takeIf { it.isNotBlank() }
                            ?: throw ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.MODEL_MISSING)

                        val agent = agentFactory.agentFor(profile, ru.outsideproarts.oassist.core.model.AssistMode.SHARE)

                        val images = payload.imageUris.take(SHARE_MAX_IMAGES).mapNotNull { uri ->
                            runCatching {
                                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()?.let { bytes ->
                                ru.outsideproarts.oassist.core.provider.ImageCodec.fromBytes(bytes)
                            }
                        }
                        val contentDesc = buildString {
                            payload.text?.let { append(getString(R.string.share_prompt_text, it)) }
                            payload.imageUris.forEach { append(getString(R.string.share_prompt_image, it)) }
                            payload.otherUris.forEach { append(getString(R.string.share_prompt_file, it)) }
                        }
                        val userMsg = getString(R.string.share_prompt_question, contentDesc, question)

                        ru.outsideproarts.oassist.core.agent.AgentSession(
                            chunks = agent.stream(
                                modelId = modelId,
                                systemPrompt = agentFactory.systemPromptFor(
                                    ru.outsideproarts.oassist.core.model.AssistMode.SHARE,
                                    sharedContent = contentDesc.ifBlank { null },
                                ),
                                history = emptyList(),
                                userMessage = userMsg,
                                images = images.ifEmpty { null },
                            ),
                            agent = agent,
                        )
                    },
                    onOpenChat = {
                        startActivity(
                            packageManager.getLaunchIntentForPackage(packageName)
                                ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        finish()
                    },
                    onClose = {
                        SpeechHandle.get()?.stop()
                        finish()
                    },
                )
            }
        }
    }
}

data class SharePayload(
    val text: String?,
    val imageUris: List<Uri>,
    val otherUris: List<Uri>,
) {
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && imageUris.isEmpty() && otherUris.isEmpty()

    companion object {
        fun from(intent: Intent?): SharePayload {
            if (intent == null) return SharePayload(null, emptyList(), emptyList())
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val images = mutableListOf<Uri>()
            val others = mutableListOf<Uri>()
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (stream != null) images.add(stream)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    if (list != null) images.addAll(list)
                }
            }
            return SharePayload(text = text, imageUris = images, otherUris = others)
        }
    }
}

@Composable
private fun ShareScreen(
    payload: SharePayload,
    onAskAgent: suspend (String) -> ru.outsideproarts.oassist.core.agent.AgentSession,
    onOpenChat: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.share_title)
    val receivedLabel = stringResource(R.string.share_received_label)
    val empty = stringResource(R.string.share_empty)
    val openChat = stringResource(R.string.share_open_chat)
    val close = stringResource(R.string.share_close)
    val askLabel = stringResource(R.string.share_ask_label)
    val sendLabel = stringResource(R.string.share_send)
    val responseLabel = stringResource(R.string.share_response)
    val speakLabel = stringResource(R.string.share_speak_answer)
    val imagesCount = stringResource(R.string.share_images_count, payload.imageUris.size)
    val context = LocalContext.current

    val textToShow = remember(payload) { payload.text?.trim().orEmpty() }

    var question by remember { mutableStateOf("") }
    var thought by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    val shareTools = remember { mutableStateListOf<ru.outsideproarts.oassist.core.agent.AgentToolEvent>() }
    var isStreaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(response) {
        // scroll handled by parent
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(OAssistDimens.ScreenContentPadding),
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .oassistHeading(),
        )

        if (payload.isEmpty) {
            OAssistPanel(title = empty, showTitle = true) {
                Text(
                    text = empty,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OAssistPanel(title = receivedLabel, showTitle = true) {
                if (textToShow.isNotEmpty()) {
                    Text(
                        text = textToShow,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (payload.imageUris.isNotEmpty()) {
                    Text(
                        text = imagesCount,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val context = LocalContext.current
                    val imageDesc = stringResource(R.string.share_image_attached)
                    payload.imageUris.take(SHARE_MAX_IMAGES).forEach { uri ->
                        var thumbBase64 by remember(uri) { mutableStateOf<String?>(null) }
                        LaunchedEffect(uri) {
                            thumbBase64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                }.getOrNull()?.let { bytes ->
                                    ru.outsideproarts.oassist.core.provider.ImageCodec.fromBytes(bytes)?.imageBase64
                                }
                            }
                        }
                        thumbBase64?.let { base64 ->
                            ru.outsideproarts.oassist.core.ui.components.OAssistImageThumbnail(
                                base64 = base64,
                                contentDescription = imageDesc,
                            )
                        }
                    }
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
                    onClick = {
                        if (question.isNotBlank() && !isStreaming) {
                            isStreaming = true
                            // Barge-in: новый вопрос прерывает озвучку предыдущего ответа.
                            SpeechHandle.get()?.stop()
                            val thoughtSb = StringBuilder()
                            val sb = StringBuilder()
                            thought = ""
                            response = ""
                            shareTools.clear()
                            scope.launch {
                                val tts = runCatching {
                                    ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                                        .modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.SHARE)
                                        .effectiveTts()
                                }.getOrElse { ru.outsideproarts.oassist.core.model.TtsConfig.DEFAULT }
                                val speech = SpeechHandle.get()
                                // Озвучка режима SHARE применяется перед каждым стримом.
                                speech?.applyTts(tts)
                                speech?.setNarrativeEnabled(tts.narrative)
                                val thoughtSpeaker = StreamSpeaker(
                                    speak = { speech?.speak(it) },
                                    speakToolCalls = tts.speakToolCalls,
                                )
                                val answerSpeaker = StreamSpeaker(
                                    speak = { speech?.speak(it) },
                                )
                                val session = onAskAgent(question.trim())
                                val error = session.chunks.collectSafe { chunk ->
                                    when (chunk) {
                                        is ChatChunk.Content -> {
                                            if (chunk.isToolComment) {
                                                thoughtSb.append(chunk.delta)
                                                thought = thoughtSb.toString()
                                                thoughtSpeaker.onText(thoughtSb.toString(), true)
                                            } else {
                                                sb.append(chunk.delta)
                                                response = sb.toString()
                                                answerSpeaker.onText(sb.toString(), false)
                                            }
                                        }
                                        is ChatChunk.ToolCall -> {
                                            shareTools.add(
                                                ru.outsideproarts.oassist.core.agent.AgentToolEvent(
                                                    name = chunk.name,
                                                ),
                                            )
                                        }
                                        is ChatChunk.ToolResult -> {
                                            val pending = shareTools.indexOfLast {
                                                it.name == chunk.name && it.output == null
                                            }
                                            val event =
                                                ru.outsideproarts.oassist.core.agent.AgentToolEvent(
                                                    name = chunk.name,
                                                    output = chunk.output,
                                                    isError = chunk.isError,
                                                )
                                            if (pending >= 0) shareTools[pending] = event
                                            else shareTools.add(event)
                                        }
                                        // В Share нет диалога: опасные действия отклоняем сразу.
                                        is ChatChunk.ApprovalNeeded -> {
                                            session.agent.resolveApproval(
                                                chunk.request.callId,
                                                ru.outsideproarts.oassist.core.model.ApprovalDecision.DENY,
                                            )
                                        }
                                        is ChatChunk.Finish -> {
                                            thoughtSpeaker.onFinish(thoughtSb.toString())
                                            answerSpeaker.onFinish(sb.toString())
                                            isStreaming = false
                                        }
                                    }
                                }
                                isStreaming = false
                                if (error != null) {
                                    Toast.makeText(context, error.userMessage(context), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = question.isNotBlank() && !isStreaming,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = sendLabel,
                    )
                }
            }

            if (response.isNotEmpty()) {
                OAssistPanel(title = responseLabel, showTitle = true) {
                    // Видимый текст — НЕ live region. Озвучка — через канал речи
                    // режима SHARE; повтор — действием «Озвучить ответ» (только ответ,
                    // без мысли и тулов).
                    Text(
                        text = response,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(speakLabel) {
                                    val speech = SpeechHandle.get() ?: return@CustomAccessibilityAction false
                                    SentenceSplitter.splitSentences(response).forEach { speech.speak(it) }
                                    true
                                },
                            )
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
        ) {
            Button(
                onClick = onOpenChat,
                modifier = Modifier.weight(1f),
            ) {
                Text(openChat)
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text(close)
            }
        }
    }
}
