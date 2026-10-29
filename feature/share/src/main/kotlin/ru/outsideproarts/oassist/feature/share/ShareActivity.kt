package ru.outsideproarts.oassist.feature.share

import ru.outsideproarts.oassist.core.model.diagnostics.launchDiagnostic
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.ChatTitler
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.ContextData
import ru.outsideproarts.oassist.core.model.ContextSource
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.provider.isEarlyRetryable
import ru.outsideproarts.oassist.core.speech.AgentSound
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SoundHandle
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.ChatDeleteDialog
import ru.outsideproarts.oassist.core.ui.components.ChatMessageBubble
import ru.outsideproarts.oassist.core.ui.components.ChatPickerList
import ru.outsideproarts.oassist.core.ui.components.ChatUnsavedDialog
import ru.outsideproarts.oassist.core.ui.components.OAssistImageThumbnail
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

private const val SHARE_MAX_IMAGES = 4

class ShareActivity : AppCompatActivity() {

    override fun onDestroy() {
        super.onDestroy()
        // Сессия шаринга кончилась — итоги использования не переживают закрытие.
        if (isFinishing) {
            runCatching { AgentFactory.get().resetUsageTotals() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = SharePayload.from(intent)
        setContent {
            OAssistTheme {
                ShareFlow(payload = payload)
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

/**
 * Шаринг в два экрана: список диалогов («Новый чат» + сохранённые, без записи
 * ассистента) → выбранный диалог с полной историей, прикреплённым файлом и
 * полем промпта. Каждый ход пишется в выбранный диалог (или создаёт новый).
 */
@Composable
private fun ShareFlow(payload: SharePayload) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatsFlow = remember {
        ChatHandle.get()?.observeChats()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val chats by chatsFlow.collectAsState(initial = emptyList())

    var inConversation by remember { mutableStateOf(false) }
    var chatId by remember { mutableStateOf<String?>(null) }
    var chatTitle by remember { mutableStateOf("") }
    var titleLocked by remember { mutableStateOf(false) }
    val turns = remember { mutableStateListOf<StoredTurn>() }
    var diagnosticOperations by remember { mutableStateOf(mutableListOf<String>()) }
    var autoSave by remember { mutableStateOf(true) }
    var images by remember { mutableStateOf<List<ImagePart>>(emptyList()) }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteAsk by remember { mutableStateOf(false) }

    var question by remember { mutableStateOf(TextFieldValue("")) }
    var activeUserMsg by remember { mutableStateOf<String?>(null) }
    var workEvents by remember { mutableStateOf<List<ru.outsideproarts.oassist.core.model.WorkEvent>>(emptyList()) }
    var response by remember { mutableStateOf("") }
    var turnUsage by remember { mutableStateOf<ru.outsideproarts.oassist.core.model.ProviderUsage?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var pendingShareRetry by remember { mutableStateOf<String?>(null) }

    val offlineRetryText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
    val repeatingText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_repeating)
    // Шаблоны промпта — через stringResource (ленивая локаль), не context.getString.
    val promptTextTemplate = stringResource(R.string.share_prompt_text)
    val promptImageTemplate = stringResource(R.string.share_prompt_image)
    val promptFileTemplate = stringResource(R.string.share_prompt_file)
    val promptQuestionTemplate = stringResource(R.string.share_prompt_question)

    LaunchedEffect(Unit) {
        autoSave = runCatching {
            AgentFactory.get().modeConfig(AssistMode.SHARE).effectiveAutoSaveChats()
        }.getOrDefault(true)
        images = withContext(Dispatchers.IO) {
            payload.imageUris.take(SHARE_MAX_IMAGES).mapNotNull { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()?.let { bytes -> ImageCodec.fromBytes(bytes) }
            }
        }
    }

    // Текстовый порядок: локальные fun объявляются до UI, которые их вызывают.
    fun generateTitle(nameSeed: String, expectedFirstTurn: StoredTurn) {
        if (titleLocked || turns.firstOrNull() !== expectedFirstTurn) return
        scope.launch(ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext(expectedFirstTurn.diagnosticOperationId ?: ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.newOperationId())) {
            if (titleLocked || turns.firstOrNull() !== expectedFirstTurn) return@launch
            val generated = runCatching {
                ChatTitler.generateTitle(AssistMode.SHARE, nameSeed, AgentFactory.get())
            }.getOrNull() ?: return@launch
            if (!titleLocked && turns.firstOrNull() === expectedFirstTurn) {
                chatTitle = generated
                val id = chatId
                if (id != null) ChatHandle.get()?.renameChat(id, generated)
            }
        }
    }

    /** Ход в БД: в сохранённый диалог, при autoSave — создать новый, иначе в память. */
    fun persistTurn(stored: StoredTurn, nameSeed: String) {
        val repo = ChatHandle.get()
        val currentId = chatId
        val isFirstTurn = turns.size == 1
        when {
            currentId != null -> {
                if (repo != null) {
                    scope.launch { repo.appendTurn(currentId, stored) }
                }
            }
            autoSave -> {
                val placeholder = ChatTitler.placeholder(nameSeed)
                if (isFirstTurn && chatTitle.isBlank()) chatTitle = placeholder
                if (repo != null) {
                    val diagnosticOwner = diagnosticOperations
                    val diagnosticSnapshot = diagnosticOwner.toList()
                    val savedTitle = chatTitle.ifBlank { placeholder }
                    scope.launch {
                        val id = repo.createChat(AssistMode.SHARE, savedTitle)
                        diagnosticSnapshot.forEach { repo.bindDiagnosticOperation(id, it) }
                        repo.appendTurn(id, stored)
                        if (chatId == null && turns.any { it === stored }) {
                            chatId = id
                        }
                        diagnosticOwner.toList().forEach { repo.bindDiagnosticOperation(id, it) }
                        if (isFirstTurn && chatId == id) generateTitle(nameSeed, stored)
                    }
                }
            }
            isFirstTurn -> {
                if (chatTitle.isBlank()) chatTitle = ChatTitler.placeholder(nameSeed)
                generateTitle(nameSeed, stored)
            }
        }
    }

    fun ask(q: String) {
        val query = q.trim()
        if (query.isBlank() || isStreaming) return
        isStreaming = true
        SpeechHandle.get()?.stop()
        val contentDesc = buildString {
            payload.text?.let { append(String.format(promptTextTemplate, it)) }
            payload.imageUris.forEach { append(String.format(promptImageTemplate, it)) }
            payload.otherUris.forEach { append(String.format(promptFileTemplate, it)) }
        }
        val userMsg = String.format(promptQuestionTemplate, contentDesc, query)
        val modelInput = ModelUserInput(
            requestText = query,
            context = if (contentDesc.isBlank()) emptyList() else {
                listOf(ContextData(ContextSource.SHARED_CONTENT, contentDesc))
            },
        )
        activeUserMsg = userMsg
        val history = turns.flatMap { it.modelMessages() }
        val timeline = ru.outsideproarts.oassist.core.agent.WorkTimelineBuilder()
        workEvents = emptyList()
        response = ""
        turnUsage = null
        var turnTranscript: List<ChatMessage> = emptyList()
        val diagnosticId = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.newOperationId()
        diagnosticOperations.add(diagnosticId)
        val diagnosticChatId = chatId
        val diagnosticRepo = ChatHandle.get()
        val diagnosticGeneration = diagnosticChatId?.let { diagnosticRepo?.diagnosticGeneration(it) }
        var turnRecorded = false
        fun recordTurn() {
            if (turnRecorded) return
            turnRecorded = true
            if (timeline.isEmpty) return
            val stored = StoredTurn(
                diagnosticOperationId = diagnosticId,
                userText = userMsg,
                modelUserInput = modelInput,
                userImages = images,
                work = timeline.work,
                answer = timeline.answerText,
                usage = timeline.usage,
                turn = turnTranscript.ifEmpty {
                    if (timeline.answerText.isNotBlank()) {
                        listOf(ChatMessage(ChatRole.ASSISTANT, timeline.answerText))
                    } else {
                        emptyList()
                    }
                },
            )
            turns.add(stored)
            activeUserMsg = null
            persistTurn(stored, nameSeed = query)
        }
        scope.launchDiagnostic("SHARE", diagnosticId) launch@ {
            if (diagnosticChatId != null && diagnosticRepo != null && diagnosticGeneration != null) {
                diagnosticRepo.bindDiagnosticOperation(diagnosticChatId, diagnosticId, diagnosticGeneration)
            }
            val tts = runCatching {
                AgentFactory.get().modeConfig(AssistMode.SHARE).effectiveTts()
            }.getOrElse { ru.outsideproarts.oassist.core.model.TtsConfig.DEFAULT }
            val soundsOn = runCatching {
                AgentFactory.get().modeConfig(AssistMode.SHARE).effectiveSoundEnabled()
            }.getOrDefault(true)
            val sounds = SoundHandle.get()
            sounds?.playIfEnabled(AgentSound.MSG_SEND, soundsOn)
            val speech = SpeechHandle.get()
            speech?.applyTts(tts)
            speech?.setNarrativeEnabled(tts.narrative)
            val thoughtSpeaker = StreamSpeaker(
                speak = { speech?.speak(it) },
                speakToolCalls = tts.speakToolCalls,
            )
            val answerSpeaker = StreamSpeaker(
                speak = { speech?.speak(it) },
            )
            val session = try {
                val factory = AgentFactory.get()
                val config = factory.modeConfig(AssistMode.SHARE)
                val profile = factory.providerForMode(AssistMode.SHARE)
                    ?: throw ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.PROVIDER_MISSING)
                val modelId = config.modelId?.takeIf { it.isNotBlank() }
                    ?: throw ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.MODEL_MISSING)
                val agent = factory.agentFor(profile, AssistMode.SHARE)
                val prompt = factory.systemPromptFor(
                    AssistMode.SHARE,
                    sharedContent = contentDesc.ifBlank { null },
                )
                AgentSession(
                    chunks = agent.stream(
                        modelId = modelId,
                        systemPrompt = prompt.instructions,
                        promptContext = prompt.context,
                        modelUserInput = modelInput,
                        history = history,
                        userMessage = userMsg,
                        images = images.ifEmpty { null },
                        onTurnMessages = { turn -> turnTranscript = turn },
                    ),
                    agent = agent,
                )
            } catch (e: CancellationException) {
                isStreaming = false
                throw e
            } catch (e: Throwable) {
                // Нет провайдера/модели — честный тост вместо падения.
                isStreaming = false
                activeUserMsg = null
                val failure = ProviderError.from(e)
                sounds?.playIfEnabled(AgentSound.ERROR, soundsOn)
                Toast.makeText(context, failure.userMessage(context), Toast.LENGTH_LONG).show()
                return@launch
            }
            val error = try {
                session.chunks.collectSafe { chunk ->
                    when (chunk) {
                        is ChatChunk.Reasoning -> {
                            // Мысли модели: в полотно, без озвучки (дельты частые).
                            if (timeline.onChunk(chunk)) workEvents = timeline.work
                        }
                        is ChatChunk.Content -> {
                            if (chunk.isToolComment) {
                                timeline.onChunk(chunk)
                                workEvents = timeline.work
                                thoughtSpeaker.onText(timeline.commentText, true)
                                sounds?.playIfEnabled(AgentSound.THINKING, soundsOn)
                            } else {
                                timeline.onChunk(chunk)
                                response = timeline.answerText
                                answerSpeaker.onText(timeline.answerText, false)
                            }
                        }
                        is ChatChunk.ToolCall -> {
                            timeline.onChunk(chunk)
                            workEvents = timeline.work
                            sounds?.playToolCallIfEnabled(chunk.name, soundsOn)
                        }
                        is ChatChunk.ToolResult -> {
                            timeline.onChunk(chunk)
                            workEvents = timeline.work
                        }
                        // В Share нет диалога подтверждений: опасные действия отклоняем сразу.
                        is ChatChunk.ApprovalNeeded -> {
                            sounds?.playIfEnabled(AgentSound.PERMISSION_DENIED, soundsOn)
                            session.agent.resolveApproval(chunk.request.callId, ApprovalDecision.DENY)
                        }
                        is ChatChunk.Finish -> {
                            timeline.onChunk(chunk)
                            thoughtSpeaker.onFinish(timeline.commentText)
                            answerSpeaker.onFinish(timeline.answerText)
                            turnUsage = timeline.usage
                            workEvents = timeline.work
                            response = timeline.answerText
                            sounds?.playIfEnabled(AgentSound.ANSWER_READY, soundsOn)
                            recordTurn()
                            if (chunk.abrupt) {
                                // Поток закрылся без терминатора: ответ мог быть усечён.
                                Toast.makeText(
                                    context,
                                    ru.outsideproarts.oassist.core.provider.R.string.provider_stream_truncated,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                isStreaming = false
                recordTurn()
            }
            if (error != null) {
                val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                if (net != null && !net.isOnline.value && error.isEarlyRetryable() &&
                    timeline.isEmpty
                ) {
                    // Упали до первого чанка без сети: ждём фронт online.
                    pendingShareRetry = query
                    activeUserMsg = null
                    Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
                } else {
                    sounds?.playIfEnabled(AgentSound.ERROR, soundsOn)
                    Toast.makeText(context, error.userMessage(context), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val netHandle = remember { ru.outsideproarts.oassist.core.net.NetworkHandle.get() }
    LaunchedEffect(netHandle) {
        var wasOnline = netHandle?.isOnline?.value ?: true
        netHandle?.isOnline?.collect { online ->
            if (online && !wasOnline) {
                val retry = pendingShareRetry
                if (retry != null && !isStreaming) {
                    pendingShareRetry = null
                    Toast.makeText(context, repeatingText, Toast.LENGTH_SHORT).show()
                    ask(retry)
                }
            }
            wasOnline = online
        }
    }

    fun backToList() {
        if (turns.isNotEmpty() && chatId == null) {
            pendingSwitch = {
                activeUserMsg = null
                inConversation = false
            }
        } else {
            activeUserMsg = null
            inConversation = false
        }
    }

    if (!inConversation) {
        ShareChatList(
            payload = payload,
            chats = chats,
            onOpenChat = { summary ->
                diagnosticOperations = mutableListOf()
                chatId = summary.id
                chatTitle = summary.title
                titleLocked = true
                turns.clear()
                activeUserMsg = null
                inConversation = true
                scope.launch {
                    val loaded = ChatHandle.get()?.loadTurns(summary.id).orEmpty()
                    turns.addAll(loaded)
                }
            },
            onNewChat = {
                diagnosticOperations = mutableListOf()
                chatId = null
                chatTitle = ""
                titleLocked = false
                turns.clear()
                activeUserMsg = null
                inConversation = true
            },
            onRenameChat = { summary, name ->
                ChatHandle.get()?.let { repo -> scope.launch { repo.renameChat(summary.id, name) } }
                if (summary.id == chatId) {
                    chatTitle = name
                    titleLocked = true
                }
            },
            onDeleteChat = { summary ->
                ChatHandle.get()?.let { repo -> scope.launch { repo.deleteChat(summary.id) } }
                if (summary.id == chatId) chatId = null
            },
            onClose = {
                SpeechHandle.get()?.stop()
                (context as? android.app.Activity)?.finish()
            },
            onOpenApp = {
                SpeechHandle.get()?.stop()
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launch)
                }
                (context as? android.app.Activity)?.finish()
            },
        )
    } else {
        ShareConversation(
            payload = payload,
            chatTitle = chatTitle,
            isSaved = chatId != null,
            turns = turns,
            activeUserMsg = activeUserMsg,
            workEvents = workEvents,
            response = response,
            usage = turnUsage,
            isStreaming = isStreaming,
            question = question,
            onQuestionChange = { question = it },
            onSend = {
                if (question.text.isNotBlank() && !isStreaming) {
                    val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                    if (net != null && !net.isOnline.value) {
                        pendingShareRetry = question.text.trim()
                        Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
                    } else {
                        pendingShareRetry = null
                        ask(question.text.trim())
                        question = TextFieldValue("")
                    }
                }
            },
            onBack = ::backToList,
            onSaveChat = {
                val repo = ChatHandle.get()
                if (chatId == null && repo != null && turns.isNotEmpty()) {
                    val snapshot = turns.toList()
                    val diagnosticSnapshot = diagnosticOperations.toList()
                    val fallback = ChatTitler.placeholder(snapshot.firstOrNull()?.userText.orEmpty())
                    if (chatTitle.isBlank()) chatTitle = fallback
                    val title = chatTitle
                    scope.launch {
                        val id = repo.createChat(AssistMode.SHARE, title)
                        diagnosticSnapshot.forEach { repo.bindDiagnosticOperation(id, it) }
                        snapshot.forEach { repo.appendTurn(id, it) }
                        chatId = id
                    }
                }
            },
            onDeleteChat = { deleteAsk = true },
        )
    }

    pendingSwitch?.let { action ->
        ChatUnsavedDialog(
            onSaveAndContinue = {
                val repo = ChatHandle.get()
                if (chatId == null && repo != null && turns.isNotEmpty()) {
                    val snapshot = turns.toList()
                    val diagnosticSnapshot = diagnosticOperations.toList()
                    val fallback = ChatTitler.placeholder(snapshot.firstOrNull()?.userText.orEmpty())
                    if (chatTitle.isBlank()) chatTitle = fallback
                    val title = chatTitle
                    scope.launch {
                        val id = repo.createChat(AssistMode.SHARE, title)
                        diagnosticSnapshot.forEach { repo.bindDiagnosticOperation(id, it) }
                        snapshot.forEach { repo.appendTurn(id, it) }
                        chatId = id
                    }
                }
                action()
                pendingSwitch = null
            },
            onContinueWithoutSaving = {
                action()
                pendingSwitch = null
            },
            onDismiss = { pendingSwitch = null },
        )
    }

    if (deleteAsk) {
        ChatDeleteDialog(
            chatTitle = chatTitle.ifBlank {
                stringResource(ru.outsideproarts.oassist.core.ui.R.string.chats_new)
            },
            onConfirm = {
                deleteAsk = false
                val id = chatId
                // Удалённый разговор не должен остаться открытым: состояние
                // чистится до «Нового чата», панель вложенной части остаётся.
                chatId = null
                chatTitle = ""
                titleLocked = false
                turns.clear()
                activeUserMsg = null
                workEvents = emptyList()
                response = ""
                turnUsage = null
                pendingShareRetry = null
                if (id != null) {
                    val repo = ChatHandle.get()
                    if (repo != null) {
                        scope.launch { repo.deleteChat(id) }
                    }
                }
            },
            onDismiss = { deleteAsk = false },
        )
    }
}

/** Первый экран шаринга: что получено + список диалогов для отправки. */
@Composable
private fun ShareChatList(
    payload: SharePayload,
    chats: List<ChatSummary>,
    onOpenChat: (ChatSummary) -> Unit,
    onNewChat: () -> Unit,
    onRenameChat: (ChatSummary, String) -> Unit,
    onDeleteChat: (ChatSummary) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.share_title)
    val receivedLabel = stringResource(R.string.share_received_label)
    val openChat = stringResource(R.string.share_open_chat)
    val close = stringResource(R.string.share_close)

    Column(
        modifier = Modifier
            .fillMaxSize()
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
        ShareReceivedPanel(payload = payload, compact = true)
        ChatPickerList(
            chats = chats,
            onOpenChat = onOpenChat,
            onNewChat = onNewChat,
            onRenameChat = onRenameChat,
            onDeleteChat = onDeleteChat,
            includeAssistant = false,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
        ) {
            Button(
                onClick = onOpenApp,
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

/** Экран диалога: история чата, прикреплённый файл и поле промпта. */
@Composable
private fun ShareConversation(
    payload: SharePayload,
    chatTitle: String,
    isSaved: Boolean,
    turns: List<StoredTurn>,
    activeUserMsg: String?,
    workEvents: List<ru.outsideproarts.oassist.core.model.WorkEvent>,
    response: String,
    usage: ru.outsideproarts.oassist.core.model.ProviderUsage?,
    isStreaming: Boolean,
    question: TextFieldValue,
    onQuestionChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onSaveChat: () -> Unit,
    onDeleteChat: () -> Unit,
) {
    val backLabel = stringResource(R.string.share_back)
    val askLabel = stringResource(R.string.share_ask_label)
    val sendLabel = stringResource(R.string.share_send)
    val speakLabel = stringResource(R.string.share_speak_answer)
    val saveLabel = stringResource(R.string.share_save_chat)
    val deleteLabel = stringResource(R.string.share_delete_chat)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(OAssistDimens.ScreenContentPadding),
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.oassistTouchTarget()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = backLabel,
                )
            }
            Text(
                text = chatTitle.ifBlank { stringResource(ru.outsideproarts.oassist.core.ui.R.string.chats_new) },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
            if (turns.isNotEmpty() || activeUserMsg != null) {
                TextButton(
                    onClick = if (isSaved) onDeleteChat else onSaveChat,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Text(if (isSaved) deleteLabel else saveLabel)
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
        ) {
            items(turns) { turn ->
                ChatMessageBubble(
                    isUser = true,
                    answer = turn.userText,
                    audio = turn.userAudio,
                    speakLabel = speakLabel,
                )
                val speakTurn: (() -> Unit)? = if (turn.answer.isNotBlank()) {
                    {
                        SpeechHandle.get()?.let { speech ->
                            SentenceSplitter.splitSentences(turn.answer).forEach { speech.speak(it) }
                        }
                    }
                } else {
                    null
                }
                ChatMessageBubble(
                    isUser = false,
                    answer = turn.answer,
                    work = turn.work,
                    usage = turn.usage,
                    onSpeak = speakTurn,
                    speakLabel = speakLabel,
                )
            }
            if (activeUserMsg != null) {
                item {
                    ChatMessageBubble(isUser = true, answer = activeUserMsg.orEmpty(), speakLabel = speakLabel)
                }
                item {
                    val speakTurn: (() -> Unit)? = if (response.isNotBlank()) {
                        {
                            SpeechHandle.get()?.let { speech ->
                                SentenceSplitter.splitSentences(response).forEach { speech.speak(it) }
                            }
                        }
                    } else {
                        null
                    }
                    ChatMessageBubble(
                        isUser = false,
                        answer = response,
                        work = workEvents,
                        usage = usage,
                        isStreaming = isStreaming,
                        onSpeak = speakTurn,
                        speakLabel = speakLabel,
                    )
                }
            }
        }

        ShareReceivedPanel(payload = payload, compact = true)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                label = { Text(askLabel) },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            )
            IconButton(
                onClick = onSend,
                enabled = question.text.isNotBlank() && !isStreaming,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = sendLabel,
                )
            }
        }
    }
}

/** Что получено: текст, количество изображений и миниатюры (компактно). */
@Composable
private fun ShareReceivedPanel(payload: SharePayload, compact: Boolean) {
    val receivedLabel = stringResource(R.string.share_received_label)
    val imagesCount = stringResource(R.string.share_images_count, payload.imageUris.size)
    val imageDesc = stringResource(R.string.share_image_attached)
    val textToShow = payload.text?.trim().orEmpty()

    OAssistPanel(title = receivedLabel, showTitle = true) {
        if (textToShow.isNotEmpty()) {
            Text(
                text = if (compact) textToShow.take(500) else textToShow,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (payload.imageUris.isNotEmpty()) {
            Text(
                text = imagesCount,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val context = LocalContext.current
            payload.imageUris.take(SHARE_MAX_IMAGES).forEach { uri ->
                var thumbBase64 by remember(uri) { mutableStateOf<String?>(null) }
                LaunchedEffect(uri) {
                    thumbBase64 = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()?.let { bytes -> ImageCodec.fromBytes(bytes)?.imageBase64 }
                    }
                }
                thumbBase64?.let { base64 ->
                    OAssistImageThumbnail(base64 = base64, contentDescription = imageDesc)
                }
            }
        }
        if (payload.otherUris.isNotEmpty()) {
            Text(
                text = payload.otherUris.joinToString("\n") { it.toString() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
