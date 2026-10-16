package ru.outsideproarts.oassist.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.AgentToolEvent
import ru.outsideproarts.oassist.core.agent.ChatTitler
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.StoredTool
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.provider.isEarlyRetryable
import ru.outsideproarts.oassist.core.speech.AgentSound
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.Narrator
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SoundHandle
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.model.SttEngine
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.ApprovalDialog
import ru.outsideproarts.oassist.core.ui.components.ChatDeleteDialog
import ru.outsideproarts.oassist.core.ui.components.ChatMessageBubble
import ru.outsideproarts.oassist.core.ui.components.ChatPickerList
import ru.outsideproarts.oassist.core.ui.components.ChatUnsavedDialog
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel

/**
 * Одна реплика ленты. У ассистента три раздельные проекции:
 * [thought] — размышление/комментарий, [tools] — полотно вызовов,
 * [answer] — основной ответ. У пользователя [answer] — текст,
 * [thought]/[tools] пусты.
 */
data class ChatItem(
    val role: ChatRole,
    val thought: String = "",
    val tools: List<AgentToolEvent> = emptyList(),
    val answer: String = "",
    val isStreaming: Boolean = false,
    /** Голосовой ход PTT (история для модели несёт байты, пузырь — плейсхолдер). */
    val audio: AudioPart? = null,
    /** Статистика хода (токены/cost от провайдера); null — провайдер не прислал. */
    val usage: ru.outsideproarts.oassist.core.model.ProviderUsage? = null,
)

/** Холд Send короче — случайный тап: отмена с хинтом, не отправка. */
private const val HOLD_MIN_MS = 500L

/** Отложенный ход: сеть пропала до/в начале стрима, повторим фронтом online. */
private data class PendingChatTurn(val text: String, val audio: AudioPart?)

/** Проекция пузыря ленты в историю модели: только основной ответ ассистента —
 * мысль и тулы уже связаны tool-протоколом и не должны дублироваться текстом. */
@Composable
fun ChatScreen(
    sendMessage: suspend (
        AssistMode,
        String,
        List<ChatMessage>,
        AudioPart?,
        (List<ChatMessage>) -> Unit,
    ) -> AgentSession,
    modifier: Modifier = Modifier,
    /** Озвучить одно завершённое предложение (неблокирующе). Null — озвучка выключена. */
    speakSentence: ((String) -> Unit)? = null,
    /** Стоп / barge-in для озвучки. */
    stopSpeech: (() -> Unit)? = null,
    /** Озвучивать комментарии к вызовам тулов (флаг TtsConfig режима). Тип тула нем всегда. */
    speakToolCalls: Boolean = true,
    /** Звуковое сопровождение действий агента (флаг ModeConfig режима). */
    soundEnabled: Boolean = true,
    /** Голосовой ввод режима (движок + язык). */
    stt: SttConfig = SttConfig.DEFAULT,
    /** У модели флаг AUDIO_IN (только ручной) — иначе MODEL_AUDIO падает в STT. */
    audioIn: Boolean = false,
    /** Лента нарратива для сворачиваемого timeline. Null — без timeline. */
    timeline: SharedFlow<Narrator.TimelineEntry>? = null,
    /** Итоги использования за жизнь процесса (in-memory). Null — без строки итогов. */
    usageTotals: kotlinx.coroutines.flow.StateFlow<ru.outsideproarts.oassist.core.model.UsageTotalsState>? = null,
) {
    val messages = remember { mutableStateListOf<ChatItem>() }
    // Состояние диалога: сохранён ли (chatId != null) и под каким именем.
    // Ходы накапливаются в памяти — источник истины для истории модели и записи в БД.
    val turnStore = remember { mutableStateListOf<StoredTurn>() }
    var chatId by remember { mutableStateOf<String?>(null) }
    var chatKind by remember { mutableStateOf(ChatKind.REGULAR) }
    var chatTitle by remember { mutableStateOf("") }
    var titleLocked by remember { mutableStateOf(false) }
    var autoSaveChats by remember { mutableStateOf(true) }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteCurrentAsk by remember { mutableStateOf(false) }
    val chatsFlow = remember {
        ChatHandle.get()?.observeChats()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val chats by chatsFlow.collectAsState(initial = emptyList())
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(Unit) {
        autoSaveChats = runCatching {
            AgentFactory.get().modeConfig(AssistMode.CHAT).effectiveAutoSaveChats()
        }.getOrDefault(true)
    }
    val timelineEntries = remember { mutableStateListOf<Narrator.TimelineEntry>() }
    val emptyTotals = remember { kotlinx.coroutines.flow.MutableStateFlow(ru.outsideproarts.oassist.core.model.UsageTotalsState()) }
    val usageTotalsState by (usageTotals ?: emptyTotals).collectAsState()
    var input by remember { mutableStateOf("") }
    /** Живой preview распознавания — НЕ в поле ввода, уходит сразу в сообщение. */
    var sttPreview by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    /** Ход, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    var pendingRetry by remember { mutableStateOf<PendingChatTurn?>(null) }
    var pendingApproval by remember { mutableStateOf<Pair<ApprovalRequest, AgentLoop>?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(timeline) {
        timeline?.collect { timelineEntries.add(it) }
    }

    val netHandle = remember { ru.outsideproarts.oassist.core.net.NetworkHandle.get() }
    val offlineRetryText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
    val repeatingText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_repeating)
    val sounds = remember { SoundHandle.get() }

    // Конец диалога (уход с экрана) — забыть «разрешено на текущую сессию»
    // и обнулить итоги использования (статистика живёт в рамках сессии чата).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val factory = ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                factory.clearSessionApprovals(ru.outsideproarts.oassist.core.model.AssistMode.CHAT)
                factory.resetUsageTotals()
            }
        }
    }

    val emptyTitle = stringResource(R.string.chat_empty_title)
    val emptyBody = stringResource(R.string.chat_empty_body)
    val sendLabel = stringResource(R.string.chat_send)
    val stopLabel = stringResource(R.string.chat_stop)
    val inputLabel = stringResource(R.string.chat_input_hint)
    val speakAnswerLabel = stringResource(R.string.chat_speak_answer)
    val context = LocalContext.current
    val micLabel = stringResource(R.string.chat_mic)
    val micDeniedText = stringResource(R.string.chat_mic_denied)
    val noRecognizerText = stringResource(R.string.chat_no_recognizer)
    val micEmptyText = stringResource(R.string.chat_mic_empty)
    val micErrorText = stringResource(R.string.chat_mic_error)
    val holdHintText = stringResource(R.string.chat_hold_hint)
    val voiceShortText = stringResource(R.string.chat_voice_short)
    val listeningText = stringResource(R.string.chat_listening)

    // Голосовой ввод — единый in-app захват (VoiceHandle поверх VoiceListener).
    // Распознанное НЕ пишется в поле: финал уходит сразу в send(), partials —
    // только в preview-статус. Порядок: send → слушатель → старт/стоп → mic.
    val voice = remember { VoiceHandle.get() }
    var capturing by remember { mutableStateOf(false) }
    LaunchedEffect(voice) {
        voice?.captureState()?.collect { capturing = it is CaptureState.Listening }
    }
    // Запуск/повтор стрима. Висящий пузырь ожидания сети (пустой, streaming)
    // переиспользуется — иначе после повтора в ленте было бы два пузыря ответа.
    // Текстовый порядок: launchTurn раньше send (локальные fun вызываются только ниже).
    fun sendMode(): AssistMode =
        if (chatKind == ChatKind.ASSISTANT) AssistMode.ASSISTANT else AssistMode.CHAT

    /** Фоновое имя диалога от модели; до ответа остаётся плейсхолдер. */
    fun generateTitle(userMsg: String) {
        if (titleLocked) return
        scope.launch {
            val generated = runCatching {
                ChatTitler.generateTitle(sendMode(), userMsg, AgentFactory.get())
            }.getOrNull() ?: return@launch
            if (!titleLocked) {
                chatTitle = generated
                chatId?.let { id -> ChatHandle.get()?.renameChat(id, generated) }
            }
        }
    }

    /**
     * Записать завершённый ход: в память (история модели) и, если диалог в
     * списке — в БД. Первый ход при autoSave создаёт диалог сразу (имя —
     * плейсхолдер, затем переименовывает модель); без autoSave диалог живёт
     * в памяти до кнопки «Сохранить чат».
     */
    fun recordTurn(
        userMsg: String,
        audio: AudioPart?,
        thought: String,
        tools: List<AgentToolEvent>,
        answer: String,
        usage: ProviderUsage?,
        turn: List<ChatMessage>,
    ) {
        turnStore.add(
            StoredTurn(
                userText = userMsg,
                userAudio = audio,
                thought = thought,
                tools = tools.map { StoredTool(it.name, it.output, it.isError, it.images) },
                answer = answer,
                usage = usage,
                turn = turn,
            ),
        )
        val repo = ChatHandle.get()
        val currentId = chatId
        val stored = turnStore.last()
        val isFirstTurn = turnStore.size == 1
        when {
            currentId != null -> {
                if (repo != null) {
                    scope.launch { repo.appendTurn(currentId, stored) }
                }
            }
            autoSaveChats -> {
                val placeholder = ChatTitler.placeholder(userMsg)
                if (isFirstTurn && chatTitle.isBlank()) chatTitle = placeholder
                if (repo != null) {
                    scope.launch {
                        val id = repo.createChat(sendMode(), chatTitle.ifBlank { placeholder })
                        chatId = id
                        repo.appendTurn(id, stored)
                        if (isFirstTurn) generateTitle(userMsg)
                    }
                }
            }
            isFirstTurn -> {
                if (chatTitle.isBlank()) chatTitle = ChatTitler.placeholder(userMsg)
                generateTitle(userMsg)
            }
        }
    }

    fun launchTurn(userMsg: String, audio: AudioPart?) {
        val history: List<ChatMessage> = turnStore.flatMap { it.modelMessages() }
        val assistantIndex: Int
        val waiting = messages.lastOrNull()
        if (waiting != null && waiting.role == ChatRole.ASSISTANT && waiting.isStreaming &&
            waiting.thought.isEmpty() && waiting.answer.isEmpty() && waiting.tools.isEmpty()
        ) {
            assistantIndex = messages.size - 1
        } else {
            assistantIndex = messages.size
            messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
        }
        streamJob = scope.launch {
            val thoughtSb = StringBuilder()
            val answerSb = StringBuilder()
            val toolEvents = mutableListOf<AgentToolEvent>()
            var turnUsage: ru.outsideproarts.oassist.core.model.ProviderUsage? = null
            // Разделение в синтезе: мысль и ответ — два независимых спикера
            // с собственными счётчиками; тулы немы всегда (раньше вырезались
            // stripServiceLines из общей каши — теперь не попадают вовсе).
            val thoughtSpeaker = StreamSpeaker(
                speak = { speakSentence?.invoke(it) },
                speakToolCalls = speakToolCalls,
            )
            val answerSpeaker = StreamSpeaker(
                speak = { speakSentence?.invoke(it) },
                speakToolCalls = true,
            )
            fun publish(streaming: Boolean) {
                messages[assistantIndex] = ChatItem(
                    role = ChatRole.ASSISTANT,
                    thought = thoughtSb.toString(),
                    tools = toolEvents.toList(),
                    answer = answerSb.toString(),
                    isStreaming = streaming,
                    usage = turnUsage,
                )
            }
            var error: ru.outsideproarts.oassist.core.provider.ProviderError? = null
            var cancelled = false
            var sawAbrupt = false
            var turnTranscript: List<ChatMessage> = emptyList()
            var turnRecorded = false
            try {
                val session = sendMessage(sendMode(), userMsg, history, audio) { turnTranscript = it }
                error = session.chunks.collectSafe { chunk ->
                    when (chunk) {
                        is ChatChunk.Content -> {
                            if (chunk.isToolComment) {
                                thoughtSb.append(chunk.delta)
                                publish(true)
                                thoughtSpeaker.onText(thoughtSb.toString(), true)
                                sounds?.playIfEnabled(AgentSound.THINKING, soundEnabled)
                            } else {
                                answerSb.append(chunk.delta)
                                publish(true)
                                answerSpeaker.onText(answerSb.toString(), false)
                            }
                        }
                        is ChatChunk.ToolCall -> {
                            toolEvents.add(AgentToolEvent(name = chunk.name))
                            publish(true)
                            sounds?.playIfEnabled(AgentSound.TOOL_CALL, soundEnabled)
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
                            publish(true)
                        }
                        is ChatChunk.ApprovalNeeded -> {
                            pendingApproval = chunk.request to session.agent
                            sounds?.playIfEnabled(AgentSound.PERMISSION_REQUEST, soundEnabled)
                        }
                        is ChatChunk.Finish -> {
                            thoughtSpeaker.onFinish(thoughtSb.toString())
                            answerSpeaker.onFinish(answerSb.toString())
                            turnUsage = chunk.usage
                            publish(false)
                            sounds?.playIfEnabled(AgentSound.ANSWER_READY, soundEnabled)
                            // Поток закрылся без терминатора: пометку покажем после
                            // сбора (`error` внутри своей лямбды-инициализатора
                            // ещё не в scope — поэтому отдельный флаг).
                            if (chunk.abrupt) sawAbrupt = true
                            recordTurn(
                                userMsg = userMsg,
                                audio = audio,
                                thought = thoughtSb.toString(),
                                tools = toolEvents.toList(),
                                answer = answerSb.toString(),
                                usage = chunk.usage,
                                turn = turnTranscript,
                            )
                            turnRecorded = true
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                // onAskAgent бросает ProviderError.NotConfigured до создания сессии
                // (нет провайдера/модели): без перехвата — падение процесса.
                error = ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
            } finally {
                // finally, а не строки после collect: при отмене
                // CancellationException пропускает всё ниже collect.
                isStreaming = false
                pendingApproval = null
                streamJob = null
                if (thoughtSb.isEmpty() && answerSb.isEmpty() && toolEvents.isEmpty() &&
                    (error != null || cancelled)
                ) {
                    messages.removeAt(assistantIndex)
                } else {
                    publish(false)
                    // Оборванный/ошибочный ход тоже попадает в историю (усечённо):
                    // транскрипт с tool-парами есть только у завершённого Finish.
                    if (!turnRecorded) {
                        val fallbackTurn = when {
                            answerSb.isNotBlank() -> listOf(ChatMessage(ChatRole.ASSISTANT, answerSb.toString()))
                            thoughtSb.isNotBlank() -> listOf(ChatMessage(ChatRole.ASSISTANT, thoughtSb.toString()))
                            else -> emptyList()
                        }
                        recordTurn(
                            userMsg = userMsg,
                            audio = audio,
                            thought = thoughtSb.toString(),
                            tools = toolEvents.toList(),
                            answer = answerSb.toString(),
                            usage = turnUsage,
                            turn = fallbackTurn,
                        )
                        turnRecorded = true
                    }
                }
            }
            if (error != null) {
                val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                if (net != null && !net.isOnline.value && error.isEarlyRetryable() &&
                    thoughtSb.isEmpty() && answerSb.isEmpty() && toolEvents.isEmpty()
                ) {
                    // Упали до первого чанка без сети (пустой пузырь уже убран в finally).
                    // Возвращаем состояние ожидания — как в пре-чеке send(): ждущий
                    // пузырь + isStreaming=true. Иначе эффект фронта не отличит
                    // ожидание от готовности и не повторит ход.
                    pendingRetry = PendingChatTurn(userMsg, audio)
                    messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
                    isStreaming = true
                    Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
                } else {
                    sounds?.playIfEnabled(AgentSound.ERROR, soundEnabled)
                    Toast.makeText(context, error.userMessage(context), Toast.LENGTH_LONG).show()
                }
            } else if (sawAbrupt) {
                // Поток закрылся без терминатора: ответ мог быть усечён.
                Toast.makeText(
                    context,
                    ru.outsideproarts.oassist.core.provider.R.string.provider_stream_truncated,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    fun send(text: String, audio: AudioPart? = null) {
        val userMsg = text.trim()
        if ((userMsg.isEmpty() && audio == null) || isStreaming) return
        messages.add(ChatItem(ChatRole.USER, answer = userMsg, audio = audio))
        isStreaming = true
        // Barge-in: новый вопрос прерывает озвучку предыдущего ответа.
        stopSpeech?.invoke()
        sounds?.playIfEnabled(AgentSound.MSG_SEND, soundEnabled)
        messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
        val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
        if (net != null && !net.isOnline.value) {
            // Оффлайн: пузыри уже в ленте, стрим стартует фронтом сети (эффект ниже).
            pendingRetry = PendingChatTurn(userMsg, audio)
            Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
            return
        }
        pendingRetry = null
        launchTurn(userMsg, audio)
    }
    // Стоп хода: отмена стрима или снятие ожидания сети. Без запущенной job
    // висящие пузыри ожидания убираются и флаг сбрасывается здесь
    // (finally(back) несуществующего стрима не будет).
    fun cancelTurn() {
        pendingRetry = null
        val hadJob = streamJob != null
        streamJob?.cancel()
        stopSpeech?.invoke()
        if (!hadJob && isStreaming) {
            messages.lastOrNull()
                ?.takeIf {
                    it.role == ChatRole.ASSISTANT && it.isStreaming &&
                        it.thought.isEmpty() && it.answer.isEmpty() && it.tools.isEmpty()
                }?.let { messages.removeAt(messages.size - 1) }
            isStreaming = false
        }
    }

    fun loadTurnsIntoUi(turns: List<StoredTurn>) {
        messages.clear()
        turns.forEach { turn ->
            messages.add(ChatItem(ChatRole.USER, answer = turn.userText, audio = turn.userAudio))
            messages.add(
                ChatItem(
                    role = ChatRole.ASSISTANT,
                    thought = turn.thought,
                    tools = turn.tools.map {
                        AgentToolEvent(name = it.name, output = it.output, isError = it.isError, images = it.images)
                    },
                    answer = turn.answer,
                    usage = turn.usage,
                ),
            )
        }
    }

    fun openChat(summary: ChatSummary) {
        cancelTurn()
        chatId = summary.id
        chatKind = summary.kind
        chatTitle = summary.title
        titleLocked = true
        turnStore.clear()
        messages.clear()
        val repo = ChatHandle.get()
        if (repo != null) {
            scope.launch {
                val turns = repo.loadTurns(summary.id)
                turnStore.addAll(turns)
                loadTurnsIntoUi(turns)
            }
        }
        scope.launch { drawerState.close() }
    }

    fun startNewChat() {
        cancelTurn()
        chatId = null
        chatKind = ChatKind.REGULAR
        chatTitle = ""
        titleLocked = false
        turnStore.clear()
        messages.clear()
        scope.launch { drawerState.close() }
    }

    /** Сохранить текущий несохранённый диалог в список (со всеми ходами). */
    fun saveCurrentChat() {
        val repo = ChatHandle.get() ?: return
        if (chatId != null || turnStore.isEmpty()) return
        val turns = turnStore.toList()
        val fallbackTitle = ChatTitler.placeholder(turns.firstOrNull()?.userText.orEmpty())
        if (chatTitle.isBlank()) chatTitle = fallbackTitle
        val title = chatTitle
        scope.launch {
            val id = repo.createChat(sendMode(), title)
            turns.forEach { repo.appendTurn(id, it) }
            chatId = id
        }
    }

    /** Смена диалога с защитой несохранённого: иначе — диалог «Сохранить/Открыть/Отмена». */
    fun requestSwitch(action: () -> Unit) {
        if (messages.isNotEmpty() && chatId == null) {
            pendingSwitch = action
        } else {
            action()
        }
    }

    fun renameChat(summary: ChatSummary, name: String) {
        ChatHandle.get()?.let { repo -> scope.launch { repo.renameChat(summary.id, name) } }
        if (summary.id == chatId) {
            chatTitle = name
            titleLocked = true
        }
    }

    fun deleteChatFromList(summary: ChatSummary) {
        ChatHandle.get()?.let { repo -> scope.launch { repo.deleteChat(summary.id) } }
        if (summary.id == chatId) {
            chatId = null
        }
    }
    // Авто-повтор отложенного хода только по фронту offline→online:
    // повтор «по уровню» дал бы петлю при флэппинге сети.
    // Позиция — после launchTurn/send/cancelTurn: эффект их вызывает,
    // локальные fun видны только ниже объявления.
    LaunchedEffect(netHandle) {
        var wasOnline = netHandle?.isOnline?.value ?: true
        netHandle?.isOnline?.collect { online ->
            if (online && !wasOnline) {
                val retry = pendingRetry
                // Стэш снимаем только при фактическом потреблении: потерянный
                // ход не должен молча исчезнуть при гонке с активным стримом.
                if (retry != null && streamJob == null && isStreaming) {
                    pendingRetry = null
                    Toast.makeText(context, repeatingText, Toast.LENGTH_SHORT).show()
                    launchTurn(retry.text, retry.audio)
                }
            }
            wasOnline = online
        }
    }
    // Слушатель системного STT: финал — сразу в send(), в поле не пишем.
    fun sttListener(): SttListener = object : SttListener {
        override fun onPartial(text: String) {
            sttPreview = text
        }

        override fun onFinal(text: String) {
            sttPreview = ""
            if (text.isNotBlank()) {
                input = ""
                send(text)
            }
        }

        override fun onSttError(error: SttError) {
            sttPreview = ""
            Toast.makeText(
                context,
                when (error) {
                    SttError.NO_SPEECH -> micEmptyText
                    SttError.NETWORK -> micErrorText
                    SttError.BUSY -> micErrorText
                    SttError.UNKNOWN -> micErrorText
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun sttErrorToast(error: CaptureError) {
        sttPreview = ""
        Toast.makeText(
            context,
            when (error) {
                CaptureError.NO_RECOGNIZER -> noRecognizerText
                CaptureError.NO_PERMISSION -> micDeniedText
                CaptureError.BUSY,
                CaptureError.INIT_FAILED -> micErrorText
            },
            Toast.LENGTH_LONG,
        ).show()
    }
    // mic-разрешение выдаём системным диалогом; дальше — сразу слушать.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice?.takeIf { it.isSttAvailable() }?.startStt(stt.language, sttListener())
                ?.let { sttErrorToast(it) }
                ?: voice?.let { sttErrorToast(CaptureError.NO_RECOGNIZER) }
        } else {
            Toast.makeText(context, micDeniedText, Toast.LENGTH_LONG).show()
        }
    }
    // Тап микрофона — toggle STT (доступная альтернатива удержанию).
    fun toggleSttMic() {
        val handle = voice ?: return
        // Barge-in: прерываем и стрим, и озвучку, и активный захват
        // (и ожидание сети, если ход отложен).
        if (isStreaming) {
            cancelTurn()
        }
        when (val current = handle.captureState().value) {
            is CaptureState.Listening -> {
                if (current.mode == ru.outsideproarts.oassist.core.speech.CaptureMode.STT) {
                    handle.stopStt()
                } else {
                    handle.cancelRaw()
                }
            }
            else -> {
                if (!handle.isSttAvailable()) {
                    sttErrorToast(CaptureError.NO_RECOGNIZER)
                    return
                }
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    handle.startStt(stt.language, sttListener())?.let { sttErrorToast(it) }
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    // Удержание Mic при пустом поле: RAW (аудио модели) или STT по режиму.
    // MODEL_AUDIO без флага AUDIO_IN у модели — молча в STT (fail-safe).
    fun useRawCapture(): Boolean = stt.engine == SttEngine.MODEL_AUDIO && audioIn
    fun startHold(): Boolean {
        val handle = voice ?: return false
        if (handle.captureState().value is CaptureState.Listening) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        if (useRawCapture()) {
            handle.startRaw()?.let { sttErrorToast(it); return false }
        } else {
            if (!handle.isSttAvailable()) {
                sttErrorToast(CaptureError.NO_RECOGNIZER)
                return false
            }
            handle.startStt(stt.language, sttListener())?.let { sttErrorToast(it); return false }
        }
        return true
    }
    // Случайное короткое касание — отмена без шума в эфир.
    fun cancelHold() {
        val handle = voice ?: return
        when ((handle.captureState().value as? CaptureState.Listening)?.mode) {
            ru.outsideproarts.oassist.core.speech.CaptureMode.STT -> handle.cancelStt()
            ru.outsideproarts.oassist.core.speech.CaptureMode.RAW -> handle.cancelRaw()
            null -> Unit
        }
        sttPreview = ""
        Toast.makeText(context, holdHintText, Toast.LENGTH_SHORT).show()
    }
    // Отпускание холда: STT — финал придёт колбэком, RAW — забираем WAV.
    fun stopHold() {
        val handle = voice ?: return
        when ((handle.captureState().value as? CaptureState.Listening)?.mode) {
            ru.outsideproarts.oassist.core.speech.CaptureMode.STT -> handle.stopStt()
            ru.outsideproarts.oassist.core.speech.CaptureMode.RAW -> {
                val wav = handle.stopRaw()
                if (wav == null) {
                    Toast.makeText(context, voiceShortText, Toast.LENGTH_SHORT).show()
                } else {
                    val base64 = android.util.Base64.encodeToString(wav.wav, android.util.Base64.NO_WRAP)
                    send(
                        "",
                        AudioPart(base64, durationSec = wav.durationSec),
                    )
                }
            }
            null -> Unit
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // Метка последнего холда: клик сразу после удержания — эхо onClick, игнор.
    var lastHoldEnd by remember { mutableStateOf(0L) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatPickerList(
                    chats = chats,
                    onOpenChat = { requestSwitch { openChat(it) } },
                    onNewChat = { requestSwitch { startNewChat() } },
                    onRenameChat = ::renameChat,
                    onDeleteChat = ::deleteChatFromList,
                )
            }
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OAssistDimens.ScreenContentPadding)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.chat_open_list),
                    )
                }
                Text(
                    text = when {
                        chatKind == ChatKind.ASSISTANT -> {
                            stringResource(ru.outsideproarts.oassist.core.ui.R.string.chats_assistant_title)
                        }
                        chatTitle.isNotBlank() -> chatTitle
                        else -> stringResource(ru.outsideproarts.oassist.core.ui.R.string.chats_new)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .oassistHeading(),
                )
                if (messages.isNotEmpty() && chatKind != ChatKind.ASSISTANT) {
                    TextButton(
                        onClick = {
                            if (chatId != null) {
                                deleteCurrentAsk = true
                            } else {
                                saveCurrentChat()
                            }
                        },
                        modifier = Modifier.oassistTouchTarget(),
                    ) {
                        Text(
                            if (chatId != null) {
                                stringResource(R.string.chat_delete_current)
                            } else {
                                stringResource(R.string.chat_save)
                            },
                        )
                    }
                }
            }

            // Лента всегда с весом: поле ввода прибито к низу и не гуляет.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
            ) {
                if (messages.isEmpty()) {
                    item {
                        OAssistPanel(title = emptyTitle) {
                            Text(
                                text = emptyBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(messages) { item ->
                        val canSpeak = speakSentence != null &&
                            item.role == ChatRole.ASSISTANT &&
                            item.answer.isNotBlank()
                        MessageBubble(
                            item = item,
                            onSpeak = if (canSpeak) {
                                {
                                    SentenceSplitter.splitSentences(item.answer).forEach {
                                        speakSentence?.invoke(it)
                                    }
                                }
                            } else {
                                null
                            },
                            speakLabel = speakAnswerLabel,
                        )
                    }
                    // Итоги всего чата — сворачиваемый блок «Ход работы» внизу ленты.
                    if (!usageTotalsState.isEmpty) {
                        item {
                            ChatTotalsBlock(totals = usageTotalsState)
                        }
                    }
                }
            }

            if (timeline != null) {
                TimelinePanel(entries = timelineEntries)
            }

            if (capturing) {
                // Статус захвата — уместный live region (не стрим контента).
                // Preview распознавания — здесь, не в поле ввода.
                val status = if (sttPreview.isBlank()) listeningText else "$listeningText $sttPreview"
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // Нижняя панель мессенджера: поле + одна action-кнопка
            // (Send / Stop / Mic). Войс — холд Mic, тап — toggle для TalkBack.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = OAssistDimens.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(inputLabel) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                val actionContent: String
                val actionIcon = when {
                    isStreaming -> {
                        actionContent = stopLabel
                        Icons.Default.Stop
                    }
                    capturing -> {
                        actionContent = stopLabel
                        Icons.Default.Stop
                    }
                    input.isBlank() -> {
                        actionContent = holdHintText
                        Icons.Default.Mic
                    }
                    else -> {
                        actionContent = sendLabel
                        Icons.AutoMirrored.Filled.Send
                    }
                }
                IconButton(
                    onClick = {
                        if (isStreaming) {
                            // Стоп: отмена коллекции снимает стрим, паузу подтверждения и HTTP-запрос.
                            // Озвучку тоже гасим (barge-in): недоговорённое не должно дочитываться.
                            // Без job — снимает ожидание сети (см. cancelTurn).
                            cancelTurn()
                            return@IconButton
                        }
                        // Эхо клика после холда — игнор.
                        if (System.currentTimeMillis() - lastHoldEnd < 300L) return@IconButton
                        if (capturing) {
                            toggleSttMic()
                            return@IconButton
                        }
                        if (input.isNotBlank()) {
                            val userMsg = input.trim()
                            input = ""
                            sttPreview = ""
                            send(userMsg)
                        } else {
                            toggleSttMic()
                        }
                    },
                    modifier = Modifier
                        .oassistTouchTarget()
                        .pointerInput(input.isBlank(), isStreaming, capturing) {
                            detectTapGestures(
                                onPress = {
                                    if (input.isBlank() && !isStreaming && !capturing && startHold()) {
                                        val downAt = System.currentTimeMillis()
                                        tryAwaitRelease()
                                        lastHoldEnd = System.currentTimeMillis()
                                        // Случайный короткий тап — отмена с хинтом, не «не расслышал».
                                        if (System.currentTimeMillis() - downAt < HOLD_MIN_MS) {
                                            cancelHold()
                                        } else {
                                            stopHold()
                                        }
                                    }
                                },
                            )
                        },
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = if (input.isBlank() && !isStreaming && !capturing) {
                            // Подсказка холда вслух; имя «Голосовой ввод» — в toggle-пути.
                            "$micLabel. $actionContent"
                        } else {
                            actionContent
                        },
                    )
                }
            }
        }
    }

    pendingApproval?.let { (request, agent) ->
        ApprovalDialog(
            request = request,
            onDecision = { decision ->
                if (decision == ApprovalDecision.DENY || decision == ApprovalDecision.ALWAYS_DENY) {
                    sounds?.playIfEnabled(AgentSound.PERMISSION_DENIED, soundEnabled)
                }
                agent.resolveApproval(request.callId, decision)
                pendingApproval = null
            },
        )
    }

    pendingSwitch?.let { action ->
        ChatUnsavedDialog(
            onSaveAndContinue = {
                saveCurrentChat()
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
    if (deleteCurrentAsk) {
        ChatDeleteDialog(
            chatTitle = chatTitle.ifBlank {
                stringResource(ru.outsideproarts.oassist.core.ui.R.string.chats_new)
            },
            onConfirm = {
                deleteCurrentAsk = false
                val id = chatId
                chatId = null
                if (id != null) {
                    val repo = ChatHandle.get()
                    if (repo != null) {
                        scope.launch { repo.deleteChat(id) }
                    }
                }
            },
            onDismiss = { deleteCurrentAsk = false },
        )
    }
}

/**
 * Реплика ленты: общий пузырь [ChatMessageBubble] (полотно «Ход работы»,
 * голосовой плейсхолдер, действие «Озвучить ответ»).
 */
@Composable
private fun MessageBubble(
    item: ChatItem,
    onSpeak: (() -> Unit)?,
    speakLabel: String,
) {
    ChatMessageBubble(
        isUser = item.role == ChatRole.USER,
        answer = item.answer,
        audio = item.audio,
        thought = item.thought,
        tools = item.tools.map {
            StoredTool(name = it.name, output = it.output, isError = it.isError, images = it.images)
        },
        usage = item.usage,
        onSpeak = onSpeak,
        speakLabel = speakLabel,
    )
}

/**
 * Итоги всего чата — сворачиваемый блок «Ход работы» внизу ленты
 * (аналог per-message WorkBlock, но без мысли/тулов — только «Всего»).
 */
@Composable
private fun ChatTotalsBlock(totals: ru.outsideproarts.oassist.core.model.UsageTotalsState) {
    val title = stringResource(R.string.chat_work_title)
    val expandedState = stringResource(R.string.chat_timeline_expanded)
    val collapsedState = stringResource(R.string.chat_timeline_collapsed)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) expandedState else collapsedState
    val toggleLabel = "$title. $state"
    val actionLabel = if (expanded) stringResource(R.string.chat_action_collapse)
                      else stringResource(R.string.chat_action_expand)

    OAssistPanel(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .semantics {
                    stateDescription = state
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            expanded = !expanded
                            true
                        },
                    )
                },
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
            ru.outsideproarts.oassist.core.ui.components.OAssistUsageTotalsLine(totals = totals)
        }
    }
}

@Composable
private fun TimelinePanel(entries: List<Narrator.TimelineEntry>) {
    val title = stringResource(R.string.chat_timeline_title)
    val emptyText = stringResource(R.string.chat_timeline_empty)
    val expandedText = stringResource(R.string.chat_timeline_expanded)
    val collapsedText = stringResource(R.string.chat_timeline_collapsed)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) expandedText else collapsedText
    val actionLabel = if (expanded) stringResource(R.string.chat_action_collapse)
                      else stringResource(R.string.chat_action_expand)

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
            val visible = entries.filter {
                it is Narrator.TimelineEntry.Thought ||
                    it is Narrator.TimelineEntry.ToolCall ||
                    it is Narrator.TimelineEntry.ToolResult
            }
            if (visible.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visible.takeLast(20).forEach { entry ->
                    Text(
                        text = timelineText(entry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun timelineText(entry: Narrator.TimelineEntry): String = when (entry) {
    is Narrator.TimelineEntry.Thought -> entry.text.take(300)
    is Narrator.TimelineEntry.ToolCall ->
        stringResource(R.string.chat_timeline_tool_call, entry.toolName)
    is Narrator.TimelineEntry.ToolResult ->
        stringResource(R.string.chat_timeline_tool_result, entry.toolName)
    is Narrator.TimelineEntry.UserMessage -> entry.text.take(300)
    is Narrator.TimelineEntry.AgentMessage -> entry.text.take(300)
}
