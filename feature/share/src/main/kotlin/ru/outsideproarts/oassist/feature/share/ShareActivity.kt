package ru.outsideproarts.oassist.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.data.prefs.SettingsHandle
import ru.outsideproarts.oassist.core.model.ChatSortOrder
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.ContextData
import ru.outsideproarts.oassist.core.model.ContextSource
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.ApprovalDialog
import ru.outsideproarts.oassist.core.ui.components.ChatDeleteDialog
import ru.outsideproarts.oassist.core.ui.components.ChatInputButton
import ru.outsideproarts.oassist.core.ui.components.ChatMessageBubble
import ru.outsideproarts.oassist.core.ui.components.ChatPickerList
import ru.outsideproarts.oassist.core.ui.components.ChatScrollRequester
import ru.outsideproarts.oassist.core.ui.components.ChatScrollToEndButton
import ru.outsideproarts.oassist.core.ui.components.ChatUnsavedDialog
import ru.outsideproarts.oassist.core.ui.components.OAssistImageThumbnail
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistLiveStatus
import ru.outsideproarts.oassist.core.ui.components.VoiceInputStrings
import ru.outsideproarts.oassist.core.ui.components.rememberChatListScroll
import ru.outsideproarts.oassist.core.ui.components.rememberVoiceInputController
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

private const val SHARE_MAX_IMAGES = 4

internal suspend fun loadShareImages(
    resolver: android.content.ContentResolver,
    imageUris: List<Uri>,
    requireAll: Boolean = false,
): List<ru.outsideproarts.oassist.core.model.ImagePart> = withContext(Dispatchers.IO) {
    imageUris.take(SHARE_MAX_IMAGES).mapNotNull { uri ->
        val bytes = try {
            resolver.openInputStream(uri)?.let { input ->
                suspendCancellableCoroutine<ByteArray> { continuation ->
                    // Android descriptor streams signal blocked readers when closed. Cancellation
                    // must close here: the reader cannot reach use's finally while waiting for EOF.
                    continuation.invokeOnCancellation { runCatching { input.close() } }
                    continuation.resumeWith(runCatching { input.use { it.readBytes() } })
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        val image = bytes?.let { ImageCodec.fromBytes(it) }
        if (requireAll && image == null) throw java.io.IOException("Could not read shared image")
        image
    }
}

class ShareActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        // Якорь анонсов скринридера (канал SCREEN_READER): фразы «Хода работы»
        // и ответа постятся от View окна (как в MainActivity). Отвязка в onPause:
        // следующее окно OAssist привязывается своим onResume уже после неё.
        runCatching { SpeechHandle.get()?.setScreenReaderAnchor(window.decorView) }
    }

    override fun onPause() {
        runCatching { SpeechHandle.get()?.setScreenReaderAnchor(null) }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Сессия шаринга кончилась — итоги использования не переживают закрытие.
        if (isFinishing) {
            runCatching { AgentFactory.get().resetUsageTotals() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = SharePayload.from(intent, contentResolver::getType)
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
    val unsupported: Boolean get() = otherUris.isNotEmpty() || imageUris.size > SHARE_MAX_IMAGES
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && imageUris.isEmpty() && otherUris.isEmpty()

    companion object {
        fun from(intent: Intent?, getType: (Uri) -> String?): SharePayload {
            if (intent == null) return SharePayload(null, emptyList(), emptyList())
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val images = mutableListOf<Uri>()
            val others = mutableListOf<Uri>()
            fun addStream(uri: Uri) {
                val type = runCatching { getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() } ?: intent.type
                if (type?.startsWith("image/", ignoreCase = true) == true) images.add(uri)
                else others.add(uri)
            }
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (stream != null) addStream(stream)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    list?.forEach(::addStream)
                }
            }
            return SharePayload(text = text, imageUris = images, otherUris = others)
        }
    }
}

/** Локализованные шаблоны описания общего контента; собираются в UI. */
private data class SharePromptTemplates(
    val text: String,
    val image: String,
    val file: String,
)

/**
 * Одноразовый тост из обработчика события. Строка резолвится через
 * [android.content.Context.getString] в момент события (а не при композиции),
 * поэтому хелпер — вне @Composable: так не срабатывает
 * `LocalContextGetResourceValueCall` и текст всегда в актуальной локали.
 */
private fun showShareToast(context: android.content.Context, toast: ShareToast) {
    val text = when (toast) {
        is ShareToast.Error -> toast.error.userMessage(context)
        is ShareToast.Res -> context.getString(toast.resId)
    }
    val duration = if (toast is ShareToast.Res && !toast.long) {
        Toast.LENGTH_SHORT
    } else {
        Toast.LENGTH_LONG
    }
    Toast.makeText(context, text, duration).show()
}

/** Сборка хода из полученного контента и вопроса; строки уже локализованы. */
private fun buildShareAsk(
    payload: SharePayload,
    query: String,
    templates: SharePromptTemplates,
): ShareAskRequest {
    val contentDesc = buildString {
        payload.text?.let { append(String.format(templates.text, it)) }
        payload.imageUris.forEach { append(String.format(templates.image, it)) }
        payload.otherUris.forEach { append(String.format(templates.file, it)) }
    }
    // Вопрос уходит сырым текстом (как в Chat); общий контент не входит в текст
    // сообщения — он передаётся данными контекста запроса (см. ShareSessionViewModel).
    val modelInput = ModelUserInput(requestText = query)
    return ShareAskRequest(query = query, userMsg = query, modelInput = modelInput, contentDesc = contentDesc)
}

/**
 * Восстановление запроса по записанному ходу для повтора генерации: ввод
 * (текст/аудио/модельный ввод) берётся из хода, общий контент — из того же
 * payload сессии, что и при исходной отправке.
 */
private fun shareAskOf(
    payload: SharePayload,
    templates: SharePromptTemplates,
    turn: ru.outsideproarts.oassist.core.model.StoredTurn,
): ShareAskRequest = ShareAskRequest(
    query = turn.userText,
    userMsg = turn.userText,
    modelInput = turn.modelUserInput ?: ModelUserInput(turn.userText),
    contentDesc = buildShareAsk(payload, "", templates).contentDesc,
    audio = turn.userAudio,
)

/**
 * Шаринг в два экрана: список диалогов («Новый чат» + сохранённые, без записи
 * ассистента) → выбранный диалог с полной историей, прикреплённым файлом и
 * полем промпта. Каждый ход пишется в выбранный диалог (или создаёт новый).
 */
@Composable
private fun ShareFlow(payload: SharePayload) {
    val context = LocalContext.current
    // Сессионное состояние — в ViewModel: переживает пересоздание Activity
    // (поворот, смена языка/темы), идущий стрим не прерывается.
    val vm: ShareSessionViewModel = viewModel()
    val chatsFlow = remember {
        ChatHandle.get()?.observeChats()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val chats by chatsFlow.collectAsState(initial = emptyList())
    val sortFlow = remember {
        SettingsHandle.get()?.chatSort
            ?: kotlinx.coroutines.flow.flowOf(ChatSortOrder.DATE_DESC)
    }
    val sortOrder by sortFlow.collectAsState(initial = ChatSortOrder.DATE_DESC)
    // UI-локальные состояния диалогов: живут в композиции, при повороте/смене
    // темы Activity не пересоздаётся, при смерти процесса диалоги закрываются.
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteAsk by remember { mutableStateOf(false) }

    // Шаблоны промпта — через stringResource (ленивая локаль), не context.getString.
    val templates = SharePromptTemplates(
        text = stringResource(R.string.share_prompt_text),
        image = stringResource(R.string.share_prompt_image),
        file = stringResource(R.string.share_prompt_file),
    )

    // Голосовой ввод — общий контроллер (toggle-STT, hold-to-talk, разрешения),
    // то же поведение, что в Chat. Конфиг режима читается один раз.
    var voiceStt by remember { mutableStateOf(ru.outsideproarts.oassist.core.model.SttConfig.DEFAULT) }
    var voiceAudioIn by remember { mutableStateOf(false) }
    var voiceSoundEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val factory = runCatching { AgentFactory.get() }.getOrNull() ?: return@LaunchedEffect
        val config = runCatching { factory.modeConfig(ru.outsideproarts.oassist.core.model.AssistMode.SHARE) }.getOrNull()
        voiceStt = config?.effectiveStt() ?: ru.outsideproarts.oassist.core.model.SttConfig.DEFAULT
        voiceSoundEnabled = config?.effectiveSoundEnabled() ?: true
        voiceAudioIn = runCatching {
            factory.modelInfo(ru.outsideproarts.oassist.core.model.AssistMode.SHARE)?.capabilities
                ?.contains(ru.outsideproarts.oassist.core.model.ModelCapability.AUDIO_IN) == true
        }.getOrDefault(false)
    }
    val isSpeaking by (SpeechHandle.get()?.speaking
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val voiceInput = rememberVoiceInputController(
        stt = voiceStt,
        audioIn = voiceAudioIn,
        strings = VoiceInputStrings(
            micDenied = stringResource(R.string.share_mic_denied),
            micEmpty = stringResource(R.string.share_mic_empty),
            micError = stringResource(R.string.share_mic_error),
            noRecognizer = stringResource(R.string.share_no_recognizer),
            btFallback = stringResource(R.string.share_mic_bt_fallback),
            voiceShort = stringResource(R.string.share_voice_short),
        ),
        soundEnabled = voiceSoundEnabled,
        canCapture = vm.canSend,
        isStreaming = vm.isStreaming,
        onBargeIn = { vm.cancelTurn() },
        stopSpeech = { SpeechHandle.get()?.stop() },
        onSendText = { text ->
            if (payload.unsupported) {
                // Отправка недоступна: распознанное не теряется — уходит в поле.
                vm.question = TextFieldValue(text)
            } else {
                val request = buildShareAsk(payload, text.trim(), templates)
                val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                if (net != null && !net.isOnline.value) vm.deferAsk(request) else vm.ask(request)
                vm.question = TextFieldValue("")
            }
        },
        onSendAudio = { audio ->
            // Голосовой ход без текста — как в Chat; пре-чек сети общий.
            if (!payload.unsupported) {
                val request = buildShareAsk(payload, "", templates)
                    .copy(userMsg = "", modelInput = ModelUserInput(""), audio = audio)
                val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                if (net != null && !net.isOnline.value) vm.deferAsk(request) else vm.ask(request)
            }
        },
    )

    // Смерть процесса: VM с историей погибает, но id открытого разговора
    // переживает в saved state — восстановление выполняется один раз на VM.
    var savedChatId by rememberSaveable { mutableStateOf("") }
    var savedInConversation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.restoreAfterProcessDeath(savedInConversation, savedChatId)
    }
    LaunchedEffect(vm.chatId, vm.inConversation) {
        savedChatId = vm.chatId.orEmpty()
        savedInConversation = vm.inConversation
    }

    LaunchedEffect(Unit) {
        val resolver = context.applicationContext.contentResolver
        if (!payload.unsupported) vm.loadPayload { loadShareImages(resolver, payload.imageUris, requireAll = true) }
    }

    // Тосты VM: строка резолвится в момент события в хелпере вне @Composable
    // (см. showToast в MainActivity) — иначе срабатывает LocalContextGetResourceValueCall.
    LaunchedEffect(Unit) {
        vm.toasts.collect { toast -> showShareToast(context, toast) }
    }

    if (!vm.inConversation) {
        ShareChatList(
            payload = payload,
            payloadLoadFailed = vm.payloadLoadFailed,
            chats = chats,
            sortOrder = sortOrder,
            onSortOrderChange = { order ->
                SettingsHandle.get()?.let { settings ->
                    vm.scope.launch { settings.setChatSort(order) }
                }
            },
            onOpenChat = vm::openChat,
            onNewChat = vm::newChat,
            onRenameChat = vm::renameChat,
            onDeleteChat = vm::deleteChatFromList,
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
            payloadLoadFailed = vm.payloadLoadFailed,
            chatTitle = vm.chatTitle,
            isSaved = vm.chatId != null,
            turns = vm.turns,
            canManageTurns = !vm.isStreaming && !vm.isChangingConversation && !vm.isSaving,
            onDeleteTurn = vm::deleteTurnMessage,
            onRollbackToTurn = vm::rollbackToTurn,
            onRegenerateTurn = { turnId ->
                vm.turns.firstOrNull { it.id == turnId }?.turn?.let { turn ->
                    vm.regenerateTurn(turnId, shareAskOf(payload, templates, turn))
                }
            },
            onRegenerateActive = vm::regenerateActive,
            activeUserMsg = vm.activeUserMsg,
            workEvents = vm.workEvents,
            response = vm.response,
            usage = vm.turnUsage,
            isStreaming = vm.isStreaming,
            canSend = vm.canSend && !payload.unsupported,
            saveFailed = vm.saveFailed,
            thinkingContextReduced = vm.providerNotice != null,
            chatId = vm.chatId,
            scrollRequester = vm.scrollRequester,
            question = vm.question,
            onQuestionChange = { vm.question = it },
            onSend = { text ->
                if (text.isNotBlank() && vm.canSend && !payload.unsupported) {
                    val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                    val request = buildShareAsk(payload, text.trim(), templates)
                    if (net != null && !net.isOnline.value) {
                        // Сеть пропала: запрос встанет в очередь на повтор
                        // и сразу покажется в ленте ожидания (как в Chat).
                        vm.deferAsk(request)
                    } else {
                        vm.ask(request)
                    }
                    vm.question = TextFieldValue("")
                }
            },
            onCancelTurn = vm::cancelTurn,
            onStopSpeech = { SpeechHandle.get()?.stop() },
            voiceInput = voiceInput,
            isSpeaking = isSpeaking,
            onBack = {
                if (vm.turns.isNotEmpty() && vm.chatId == null) {
                    pendingSwitch = { vm.leaveConversation() }
                } else {
                    vm.leaveConversation()
                }
            },
            onSaveChat = vm::saveChat,
            onRetryWrites = vm::retryWrites,
            onDeleteChat = { deleteAsk = true },
        )
    }

    pendingSwitch?.let { action ->
        ChatUnsavedDialog(
            onSaveAndContinue = {
                vm.saveChat()
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

    // Подтверждения действий — общий диалог, как в Chat.
    vm.pendingApproval?.let { (request, _) ->
        ApprovalDialog(
            request = request,
            onDecision = { decision -> vm.resolveApproval(decision) },
        )
    }

    if (deleteAsk) {
        ChatDeleteDialog(
            chatTitle = vm.chatTitle.ifBlank {
                stringResource(ru.outsideproarts.oassist.core.ui.R.string.chats_new)
            },
            onConfirm = {
                deleteAsk = false
                vm.deleteCurrentChat()
            },
            onDismiss = { deleteAsk = false },
        )
    }
}

/** Первый экран шаринга: что получено + список диалогов для отправки. */
@Composable
private fun ShareChatList(
    payload: SharePayload,
    payloadLoadFailed: Boolean,
    chats: List<ChatSummary>,
    sortOrder: ChatSortOrder,
    onSortOrderChange: (ChatSortOrder) -> Unit,
    onOpenChat: (ChatSummary) -> Unit,
    onNewChat: () -> Unit,
    onRenameChat: (ChatSummary, String) -> Unit,
    onDeleteChat: (ChatSummary) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.share_title)
    val openChat = stringResource(R.string.share_open_chat)
    val close = stringResource(R.string.share_close)

    ChatPickerList(
        chats = chats,
        sortOrder = sortOrder,
        onSortOrderChange = onSortOrderChange,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onRenameChat = onRenameChat,
        onDeleteChat = onDeleteChat,
        includeAssistant = false,
        scrollEntireContent = true,
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(OAssistDimens.ScreenContentPadding),
        leadingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().oassistHeading(),
                )
                ShareReceivedPanel(payload = payload, compact = true, payloadLoadFailed = payloadLoadFailed)
            }
        },
        trailingContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
            ) {
                Button(onClick = onOpenApp, modifier = Modifier.weight(1f)) { Text(openChat) }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text(close) }
            }
        },
    )
}

/** Ключ памяти позиций прокрутки для несохранённого черновика окна «Отправить». */
private const val ShareDraftScrollKey = "draft:share"

/** Экран диалога: история чата, прикреплённый файл и поле промпта. */
@Composable
private fun ShareConversation(
    payload: SharePayload,
    payloadLoadFailed: Boolean,
    chatTitle: String,
    isSaved: Boolean,
    turns: List<ru.outsideproarts.oassist.core.model.StoredTurnEntry>,
    /** Действия с сообщениями доступны вне стрима/записи/смены разговора. */
    canManageTurns: Boolean,
    onDeleteTurn: (String) -> Unit,
    onRollbackToTurn: (String) -> Unit,
    onRegenerateTurn: (String) -> Unit,
    onRegenerateActive: () -> Unit,
    activeUserMsg: String?,
    workEvents: List<ru.outsideproarts.oassist.core.model.WorkEvent>,
    response: String,
    usage: ru.outsideproarts.oassist.core.model.ProviderUsage?,
    isStreaming: Boolean,
    canSend: Boolean,
    saveFailed: Boolean,
    thinkingContextReduced: Boolean,
    chatId: String?,
    scrollRequester: ChatScrollRequester,
    question: TextFieldValue,
    onQuestionChange: (TextFieldValue) -> Unit,
    onSend: (String) -> Unit,
    onCancelTurn: () -> Unit,
    onStopSpeech: () -> Unit,
    voiceInput: ru.outsideproarts.oassist.core.ui.components.VoiceInputController,
    isSpeaking: Boolean,
    onBack: () -> Unit,
    onSaveChat: () -> Unit,
    onRetryWrites: () -> Unit,
    onDeleteChat: () -> Unit,
) {
    val backLabel = stringResource(R.string.share_back)
    val askLabel = stringResource(R.string.share_ask_label)
    val sendLabel = stringResource(R.string.share_send)
    val stopLabel = stringResource(R.string.share_stop)
    val stopSpeechLabel = stringResource(R.string.share_stop_speech)
    val speakingLabel = stringResource(R.string.share_speaking)
    val micLabel = stringResource(R.string.share_mic)
    val holdHintText = stringResource(R.string.share_hold_hint)
    val holdActionLabel = stringResource(R.string.share_hold_action)
    val startVoiceLabel = stringResource(R.string.share_mic_start)
    val stopVoiceLabel = stringResource(R.string.share_mic_stop)
    val micIdleLabel = stringResource(R.string.share_mic_idle)
    val listeningText = stringResource(R.string.share_listening)
    val speakLabel = stringResource(R.string.share_speak_answer)
    val saveLabel = stringResource(R.string.share_save_chat)
    val deleteLabel = stringResource(R.string.share_delete_chat)

    val listState = rememberLazyListState()
    val scrollState = rememberChatListScroll(
        listState = listState,
        requester = scrollRequester,
        chatId = chatId,
        draftKey = ShareDraftScrollKey,
        contentKey = listOf(turns.size, activeUserMsg, response, workEvents, isStreaming),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
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
                if (saveFailed) {
                    TextButton(onClick = onRetryWrites, modifier = Modifier.oassistTouchTarget()) {
                        Text(stringResource(R.string.share_retry_save))
                    }
                }
                TextButton(
                    onClick = if (isSaved) onDeleteChat else onSaveChat,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Text(if (isSaved) deleteLabel else saveLabel)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
            ) {
                items(turns) { entry ->
                    val turn = entry.turn
                    val managedId = if (canManageTurns) entry.id else null
                    val onDelete: (() -> Unit)? = managedId?.let { id -> ({ onDeleteTurn(id) }) }
                    // Откат скрыт на последнем ходе: после него стирать нечего.
                    val onRollback: (() -> Unit)? = managedId
                        ?.takeIf { id -> turns.lastOrNull()?.id != id }
                        ?.let { id -> ({ onRollbackToTurn(id) }) }
                    val onRegenerate: (() -> Unit)? = managedId?.let { id -> ({ onRegenerateTurn(id) }) }
                    // Подтверждение нужно, когда повтор унесёт последующие сообщения:
                    // это и хвост ленты, и осиротевшее активное сообщение после хода.
                    val needsRegenerateConfirm = turns.lastOrNull()?.id != entry.id || activeUserMsg != null
                    ChatMessageBubble(
                        isUser = true,
                        answer = turn.userText,
                        audio = turn.userAudio,
                        speakLabel = speakLabel,
                        onDelete = onDelete,
                        onRollback = onRollback,
                        onRegenerate = onRegenerate,
                        needsRegenerateConfirm = needsRegenerateConfirm,
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
                        onDelete = onDelete,
                        onRollback = onRollback,
                        onRegenerate = onRegenerate,
                        needsRegenerateConfirm = needsRegenerateConfirm,
                    )
                }
                if (activeUserMsg != null) {
                    item {
                        ChatMessageBubble(
                            isUser = true,
                            answer = activeUserMsg.orEmpty(),
                            speakLabel = speakLabel,
                            // Реплика без записанного хода (ошибка/стоп): повтор
                            // доступен и на ней, стирать нечего — без подтверждения.
                            onRegenerate = if (canManageTurns) ({ onRegenerateActive() }) else null,
                            needsRegenerateConfirm = false,
                        )
                    }
                    // Пузырь ассистента — только с содержимым или в процессе стрима:
                    // отменённый пустой ход не оставляет пустой ответ (как в Chat).
                    if (isStreaming || workEvents.isNotEmpty() || response.isNotBlank()) {
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
                item {
                    ShareReceivedPanel(payload = payload, compact = true, payloadLoadFailed = payloadLoadFailed)
                }
            }
            ChatScrollToEndButton(
                state = scrollState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(OAssistDimens.ItemSpacing),
            )
        }

        ru.outsideproarts.oassist.core.ui.components.ThinkingContextNotice(thinkingContextReduced)
        if (voiceInput.capturing) {
            // Статус захвата — уместный live region (не стрим контента).
            // Preview распознавания — здесь, не в поле ввода (как в Chat).
            val status = if (voiceInput.preview.isBlank()) listeningText else "$listeningText ${voiceInput.preview}"
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
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
            // Единая action-кнопка (Send / Stop / Mic) — как в Chat.
            // Войс — холд Mic, тап — toggle для TalkBack.
            val actionContent: String
            val actionIcon = when {
                isStreaming -> {
                    actionContent = stopLabel
                    Icons.Filled.Stop
                }
                voiceInput.capturing -> {
                    actionContent = stopLabel
                    Icons.Filled.Stop
                }
                isSpeaking -> {
                    actionContent = stopSpeechLabel
                    Icons.Filled.Stop
                }
                question.text.isBlank() -> {
                    actionContent = holdHintText
                    Icons.Filled.Mic
                }
                else -> {
                    actionContent = sendLabel
                    Icons.AutoMirrored.Filled.Send
                }
            }
            ChatInputButton(
                icon = actionIcon,
                label = if (question.text.isBlank() && !isStreaming && !voiceInput.capturing && !isSpeaking) {
                    "$micLabel. $holdHintText"
                } else {
                    actionContent
                },
                holdLabel = holdActionLabel,
                canStartHold = (question.text.isBlank() || isSpeaking) && !isStreaming && !voiceInput.capturing && canSend,
                holdEnabled = !isStreaming && canSend &&
                    (question.text.isBlank() || voiceInput.capturing || isSpeaking || voiceInput.holdActive),
                onVoiceAction = { voiceInput.toggle() },
                onHoldStart = voiceInput::startHold,
                onHoldRelease = voiceInput::releaseHold,
                onHoldCancel = voiceInput::cancelHold,
                voiceActionLabel = if (!isStreaming && (question.text.isBlank() || voiceInput.capturing || isSpeaking)) {
                    if (voiceInput.capturing) stopVoiceLabel else startVoiceLabel
                } else {
                    null
                },
                voiceStateLabel = if (!isStreaming && (question.text.isBlank() || voiceInput.capturing || isSpeaking)) {
                    when {
                        voiceInput.capturing -> listeningText
                        isSpeaking -> speakingLabel
                        else -> micIdleLabel
                    }
                } else {
                    null
                },
                onClick = {
                    if (isStreaming) {
                        onCancelTurn()
                        return@ChatInputButton
                    }
                    if (voiceInput.capturing) {
                        voiceInput.toggle()
                        return@ChatInputButton
                    }
                    if (isSpeaking) {
                        onStopSpeech()
                        return@ChatInputButton
                    }
                    if (question.text.isNotBlank()) {
                        val text = question.text.trim()
                        onQuestionChange(TextFieldValue(""))
                        voiceInput.clearPreview()
                        onSend(text)
                    } else {
                        voiceInput.toggle()
                    }
                },
            )
        }
    }
}

/** Что получено: текст, количество изображений и миниатюры (компактно). */
@Composable
private fun ShareReceivedPanel(payload: SharePayload, compact: Boolean, payloadLoadFailed: Boolean) {
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
                    thumbBase64 = loadShareImages(context.contentResolver, listOf(uri))
                        .firstOrNull()?.imageBase64
                }
                thumbBase64?.let { base64 ->
                    OAssistImageThumbnail(base64 = base64, contentDescription = imageDesc)
                }
            }
        }
        if (payload.unsupported || payloadLoadFailed) {
            OAssistLiveStatus(text = stringResource(R.string.share_unsupported_file), horizontalPadding = 0.dp)
        }
    }
}
