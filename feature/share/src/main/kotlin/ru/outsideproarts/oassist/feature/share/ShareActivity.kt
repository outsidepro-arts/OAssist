package ru.outsideproarts.oassist.feature.share

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
import androidx.compose.ui.text.input.TextFieldValue
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
import ru.outsideproarts.oassist.core.ui.components.ChatDeleteDialog
import ru.outsideproarts.oassist.core.ui.components.ChatMessageBubble
import ru.outsideproarts.oassist.core.ui.components.ChatPickerList
import ru.outsideproarts.oassist.core.ui.components.ChatUnsavedDialog
import ru.outsideproarts.oassist.core.ui.components.OAssistImageThumbnail
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

private const val SHARE_MAX_IMAGES = 4

internal suspend fun loadShareImages(
    resolver: android.content.ContentResolver,
    imageUris: List<Uri>,
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
        bytes?.let { ImageCodec.fromBytes(it) }
    }
}

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

/** Локализованные шаблоны описания общего контента; собираются в UI. */
private data class SharePromptTemplates(
    val text: String,
    val image: String,
    val file: String,
    val question: String,
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
    val userMsg = String.format(templates.question, contentDesc, query)
    val modelInput = ModelUserInput(
        requestText = query,
        context = if (contentDesc.isBlank()) emptyList() else {
            listOf(ContextData(ContextSource.SHARED_CONTENT, contentDesc))
        },
    )
    return ShareAskRequest(query = query, userMsg = userMsg, modelInput = modelInput, contentDesc = contentDesc)
}

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

    val offlineRetryText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
    // Шаблоны промпта — через stringResource (ленивая локаль), не context.getString.
    val templates = SharePromptTemplates(
        text = stringResource(R.string.share_prompt_text),
        image = stringResource(R.string.share_prompt_image),
        file = stringResource(R.string.share_prompt_file),
        question = stringResource(R.string.share_prompt_question),
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
        vm.loadPayload { loadShareImages(resolver, payload.imageUris) }
    }

    // Тосты VM: строка резолвится в момент события в хелпере вне @Composable
    // (см. showToast в MainActivity) — иначе срабатывает LocalContextGetResourceValueCall.
    LaunchedEffect(Unit) {
        vm.toasts.collect { toast -> showShareToast(context, toast) }
    }

    if (!vm.inConversation) {
        ShareChatList(
            payload = payload,
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
            chatTitle = vm.chatTitle,
            isSaved = vm.chatId != null,
            turns = vm.turns,
            activeUserMsg = vm.activeUserMsg,
            workEvents = vm.workEvents,
            response = vm.response,
            usage = vm.turnUsage,
            isStreaming = vm.isStreaming,
            canSend = vm.canSend,
            saveFailed = vm.saveFailed,
            thinkingContextReduced = vm.providerNotice != null,
            question = vm.question,
            onQuestionChange = { vm.question = it },
            onSend = {
                if (vm.question.text.isNotBlank() && vm.canSend) {
                    val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                    val request = buildShareAsk(payload, vm.question.text.trim(), templates)
                    if (net != null && !net.isOnline.value) {
                        // Сеть пропала: запрос встанет в очередь на повтор.
                        vm.deferAsk(request)
                        Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
                    } else {
                        vm.ask(request)
                        vm.question = TextFieldValue("")
                    }
                }
            },
            onBack = {
                if (vm.turns.isNotEmpty() && vm.chatId == null) {
                    pendingSwitch = { vm.leaveConversation() }
                } else {
                    vm.leaveConversation()
                }
            },
            onSaveChat = vm::saveChat,
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
            sortOrder = sortOrder,
            onSortOrderChange = onSortOrderChange,
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
    canSend: Boolean,
    saveFailed: Boolean,
    thinkingContextReduced: Boolean,
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
                if (saveFailed) {
                    TextButton(onClick = onSaveChat, modifier = Modifier.oassistTouchTarget()) {
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

        ru.outsideproarts.oassist.core.ui.components.ThinkingContextNotice(thinkingContextReduced)
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
                enabled = question.text.isNotBlank() && canSend,
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
                    thumbBase64 = loadShareImages(context.contentResolver, listOf(uri))
                        .firstOrNull()?.imageBase64
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
