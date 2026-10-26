package ru.outsideproarts.oassist.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.semantics.semantics
import android.widget.Toast
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.ChatTitler
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.provider.isEarlyRetryable
import ru.outsideproarts.oassist.core.speech.AgentSound
import ru.outsideproarts.oassist.core.speech.BtInputDecision
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SoundHandle
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.VoiceInputRoute
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
 * [work] — полотно «Ход работы» (мыслевые блоки и тулы в хронологии),
 * [answer] — основной ответ. У пользователя [answer] — текст, [work] пусто.
 */
data class ChatItem(
    val role: ChatRole,
    val work: List<ru.outsideproarts.oassist.core.model.WorkEvent> = emptyList(),
    val answer: String = "",
    val isStreaming: Boolean = false,
    /** Голосовой ход PTT (история для модели несёт байты, пузырь — плейсхолдер). */
    val audio: AudioPart? = null,
    /** Статистика хода (токены/cost от провайдера); null — провайдер не прислал. */
    val usage: ru.outsideproarts.oassist.core.model.ProviderUsage? = null,
)

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
    /**
     * Сессия чата (ViewModel activity-скоупа): лента/ходы/черновик/стрим
     * переживают переключение вкладок, пока активно приложение.
     */
    viewModel: ChatSessionViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    // Состояние диалога живёт в ChatSessionViewModel; локальные `by vm::field`
    // дают те же имена переменных с реактивностью snapshot-состояния.
    val messages = viewModel.messages
    val turnStore = viewModel.turnStore
    var chatId by viewModel::chatId
    var chatKind by viewModel::chatKind
    var chatTitle by viewModel::chatTitle
    var titleLocked by viewModel::titleLocked
    var autoSaveChats by viewModel::autoSaveChats
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteCurrentAsk by remember { mutableStateOf(false) }
    /** Окно «Информация о чате» (по тапу на заголовок) вместо ленты. */
    var showChatInfo by remember { mutableStateOf(false) }
    // Системный жест «Назад» закрывает статистику и возвращает в чат,
    // а не сворачивает приложение.
    androidx.activity.compose.BackHandler(enabled = showChatInfo) {
        showChatInfo = false
    }
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
    var input by viewModel::input
    /** Живой preview распознавания — НЕ в поле ввода, уходит сразу в сообщение. */
    var sttPreview by remember { mutableStateOf("") }
    var isStreaming by viewModel::isStreaming
    var streamJob by viewModel::streamJob
    /** Ход, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    var pendingRetry by viewModel::pendingRetry
    var pendingApproval by viewModel::pendingApproval
    // Скоуп сессии: корутины чата не прерываются при уходе на вкладку настроек.
    val scope = viewModel.scope
    // UI-скоуп композиции: только для анимаций Compose (drawerState.open/close).
    // У viewModel.scope нет MonotonicFrameClock, анимация из него падает.
    val uiScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val netHandle = remember { ru.outsideproarts.oassist.core.net.NetworkHandle.get() }
    val offlineRetryText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
    val repeatingText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_repeating)
    val sounds = remember { SoundHandle.get() }

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
    val micBtFallbackText = stringResource(R.string.chat_mic_bt_fallback)
    val listeningText = stringResource(R.string.chat_listening)
    val holdActionLabel = stringResource(R.string.chat_hold_action)
    val startVoiceLabel = stringResource(R.string.chat_mic_start)
    val stopVoiceLabel = stringResource(R.string.chat_mic_stop)
    val micIdleLabel = stringResource(R.string.chat_mic_idle)

    // Голосовой ввод — единый in-app захват (VoiceHandle поверх VoiceListener).
    // Распознанное не пишется в поле; удержание подтверждается отпусканием, partials —
    // только в preview-статус. Порядок: send → слушатель → старт/стоп → mic.
    val voice = remember { VoiceHandle.get() }
    var capturing by remember { mutableStateOf(false) }
    var holdSession by remember { mutableStateOf<ChatVoiceHoldSession?>(null) }
    LaunchedEffect(voice) {
        voice?.captureState()?.collect { capturing = it is CaptureState.Listening }
    }
    // Запуск/повтор стрима. Висящий пузырь ожидания сети (пустой, streaming)
    // переиспользуется — иначе после повтора в ленте было бы два пузыря ответа.
    // Текстовый порядок: launchTurn раньше send (локальные fun вызываются только ниже).
    fun sendMode(): AssistMode =
        if (chatKind == ChatKind.ASSISTANT) AssistMode.ASSISTANT else AssistMode.CHAT

    /** Фоновое имя диалога от модели; до ответа остаётся плейсхолдер. */
    fun generateTitle(userMsg: String, expectedFirstTurn: StoredTurn) {
        if (titleLocked || turnStore.firstOrNull() !== expectedFirstTurn) return
        scope.launch {
            if (titleLocked || turnStore.firstOrNull() !== expectedFirstTurn) return@launch
            val generated = runCatching {
                ChatTitler.generateTitle(sendMode(), userMsg, AgentFactory.get(), firstAnswer = expectedFirstTurn.answer)
            }.getOrNull() ?: return@launch
            if (!titleLocked && turnStore.firstOrNull() === expectedFirstTurn) {
                chatTitle = generated
                val id = chatId
                if (id != null) ChatHandle.get()?.renameChat(id, generated)
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
        work: List<ru.outsideproarts.oassist.core.model.WorkEvent>,
        answer: String,
        usage: ProviderUsage?,
        turn: List<ChatMessage>,
    ) {
        turnStore.add(
            StoredTurn(
                userText = userMsg,
                userAudio = audio,
                work = work,
                answer = answer,
                usage = usage,
                turn = turn,
            ),
        )
        val repo = ChatHandle.get()
        val currentId = chatId
        val stored = turnStore.last()
        val isFirstTurn = turnStore.size == 1
        // Аудио-ход для модели не имеет текста: имя строится по ответу ассистента.
        val nameSeed = userMsg.ifBlank { answer }
        when {
            currentId != null -> {
                if (repo != null) {
                    scope.launch { repo.appendTurn(currentId, stored) }
                }
            }
            autoSaveChats -> {
                val placeholder = ChatTitler.placeholder(nameSeed)
                if (isFirstTurn && chatTitle.isBlank()) chatTitle = placeholder
                if (repo != null) {
                    scope.launch {
                        val id = repo.createChat(sendMode(), chatTitle.ifBlank { placeholder })
                        chatId = id
                        repo.appendTurn(id, stored)
                        if (isFirstTurn) generateTitle(userMsg, stored)
                    }
                }
            }
            isFirstTurn -> {
                if (chatTitle.isBlank()) chatTitle = ChatTitler.placeholder(nameSeed)
                generateTitle(userMsg, stored)
            }
        }
    }

    fun launchTurn(userMsg: String, audio: AudioPart?) {
        val history: List<ChatMessage> = turnStore.flatMap { it.modelMessages() }
        val assistantIndex: Int
        val waiting = messages.lastOrNull()
        if (waiting != null && waiting.role == ChatRole.ASSISTANT && waiting.isStreaming &&
            waiting.work.isEmpty() && waiting.answer.isEmpty()
        ) {
            assistantIndex = messages.size - 1
        } else {
            assistantIndex = messages.size
            messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
        }
        streamJob = scope.launch {
            // Хронологическое полотно (мыслевые блоки ↔ тулы) + ответ + usage.
            val timeline = ru.outsideproarts.oassist.core.agent.WorkTimelineBuilder()
            // Разделение в синтезе: комментарии и ответ — два независимых спикера
            // с собственными счётчиками; reasoning и тулы немы всегда.
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
                    work = timeline.work,
                    answer = timeline.answerText,
                    isStreaming = streaming,
                    usage = timeline.usage,
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
                        is ChatChunk.Reasoning -> {
                            // Мысли модели: live в полотно, без озвучки и без звука
                            // (дельты частые — звук затапил бы очередь).
                            if (timeline.onChunk(chunk)) publish(true)
                        }
                        is ChatChunk.Content -> {
                            if (chunk.isToolComment) {
                                timeline.onChunk(chunk)
                                publish(true)
                                thoughtSpeaker.onText(timeline.commentText, true)
                                sounds?.playIfEnabled(AgentSound.THINKING, soundEnabled)
                            } else {
                                timeline.onChunk(chunk)
                                publish(true)
                                answerSpeaker.onText(timeline.answerText, false)
                            }
                        }
                        is ChatChunk.ToolCall -> {
                            timeline.onChunk(chunk)
                            publish(true)
                            sounds?.playToolCallIfEnabled(chunk.name, soundEnabled)
                        }
                        is ChatChunk.ToolResult -> {
                            timeline.onChunk(chunk)
                            publish(true)
                        }
                        is ChatChunk.ApprovalNeeded -> {
                            pendingApproval = chunk.request to session.agent
                            sounds?.playIfEnabled(AgentSound.PERMISSION_REQUEST, soundEnabled)
                        }
                        is ChatChunk.Finish -> {
                            timeline.onChunk(chunk)
                            thoughtSpeaker.onFinish(timeline.commentText)
                            answerSpeaker.onFinish(timeline.answerText)
                            publish(false)
                            sounds?.playIfEnabled(AgentSound.ANSWER_READY, soundEnabled)
                            // Поток закрылся без терминатора: пометку покажем после
                            // сбора (`error` внутри своей лямбды-инициализатора
                            // ещё не в scope — поэтому отдельный флаг).
                            if (chunk.abrupt) sawAbrupt = true
                            recordTurn(
                                userMsg = userMsg,
                                audio = audio,
                                work = timeline.work,
                                answer = timeline.answerText,
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
                if (timeline.isEmpty && (error != null || cancelled)) {
                    messages.removeAt(assistantIndex)
                } else {
                    publish(false)
                    // Оборванный/ошибочный ход тоже попадает в историю (усечённо):
                    // транскрипт с tool-парами есть только у завершённого Finish.
                    if (!turnRecorded) {
                        val fallbackTurn = when {
                            timeline.answerText.isNotBlank() ->
                                listOf(ChatMessage(ChatRole.ASSISTANT, timeline.answerText))
                            timeline.commentText.isNotBlank() ->
                                listOf(ChatMessage(ChatRole.ASSISTANT, timeline.commentText))
                            else -> emptyList()
                        }
                        recordTurn(
                            userMsg = userMsg,
                            audio = audio,
                            work = timeline.work,
                            answer = timeline.answerText,
                            usage = timeline.usage,
                            turn = fallbackTurn,
                        )
                        turnRecorded = true
                    }
                }
            }
            if (error != null) {
                val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                if (net != null && !net.isOnline.value && error.isEarlyRetryable() &&
                    timeline.isEmpty
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
                        it.work.isEmpty() && it.answer.isEmpty()
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
                    work = turn.work,
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
        uiScope.launch { drawerState.close() }
    }

    /**
     * Сбросить текущий диалог в пустой новый (без закрытия шторки):
     * гасит идущий стрим, чистит ленту/ходы/имя. Используется «Новым чатом»
     * и удалением открытого диалога — удалённый не должен остаться на экране.
     */
    fun resetChat() {
        cancelTurn()
        chatId = null
        chatKind = ChatKind.REGULAR
        chatTitle = ""
        titleLocked = false
        turnStore.clear()
        messages.clear()
        pendingApproval = null
    }

    fun startNewChat() {
        resetChat()
        uiScope.launch { drawerState.close() }
    }

    /** Сохранить текущий несохранённый диалог в список (со всеми ходами). */
    fun saveCurrentChat() {
        val repo = ChatHandle.get() ?: return
        if (chatId != null || turnStore.isEmpty()) return
        val turns = turnStore.toList()
        // Аудио-ход без текста: имя — из первого текстового хода или ответа.
        val fallbackTitle = ChatTitler.placeholder(
            turns.firstOrNull { it.userText.isNotBlank() }?.userText
                ?: turns.firstOrNull { it.answer.isNotBlank() }?.answer.orEmpty(),
        )
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

    /**
     * Переименование из окна «Информация о чате»: имя сразу фиксируется
     * (titleLocked — авто-именование больше не трогает) и, если диалог
     * сохранён, пишется в БД; несохранённый живёт только в памяти — имя
     * применится при сохранении (createChat читает chatTitle).
     */
    fun renameCurrentChat(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        chatTitle = clean
        titleLocked = true
        val id = chatId ?: return
        ChatHandle.get()?.let { repo -> scope.launch { repo.renameChat(id, clean) } }
    }

    fun deleteChatFromList(summary: ChatSummary) {
        ChatHandle.get()?.let { repo -> scope.launch { repo.deleteChat(summary.id) } }
        if (summary.id == chatId) {
            // Удалили открытый диалог — сбросить вид в пустой новый
            // (шторка остаётся: сброс её не закрывает).
            resetChat()
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
    // Итог удержания отправляется только после принятого отпускания.
    fun sendRecognizedText(text: String) {
        if (text.isNotBlank()) {
            input = TextFieldValue("")
            send(text)
        }
    }
    fun sttListener(hold: ChatVoiceHoldSession? = null): SttListener = object : SttListener {
        override fun onPartial(text: String) {
            sttPreview = text
        }

        override fun onFinal(text: String) {
            sttPreview = ""
            sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
            if (hold == null) sendRecognizedText(text) else hold.onFinal(text)?.let(::sendRecognizedText)
        }

        override fun onSttError(error: SttError) {
            hold?.onError()
            sttPreview = ""
            sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
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
    // Старт toggle-STT после выданных разрешений.
    fun startToggleStt() {
        val handle = voice
        if (handle == null || !handle.isSttAvailable(stt.recognizerComponent)) {
            sttErrorToast(CaptureError.NO_RECOGNIZER)
            return
        }
        val error = handle.startStt(stt.language, sttListener(), stt.recognizerComponent, stt.bluetoothInput)
        if (error != null) sttErrorToast(error)
        else sounds?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
    }
    // mic-разрешение выдаём системным диалогом; дальше — сразу слушать.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startToggleStt()
        } else {
            Toast.makeText(context, micDeniedText, Toast.LENGTH_LONG).show()
        }
    }
    // BLUETOOTH_CONNECT запрашиваем один раз при подключённой гарнитуре; отказ
    // не блокирует запись с микрофона. Старт возобновляет только toggle — из
    // холда палец к этому моменту уже поднят.
    val pendingBtStart = remember { mutableStateOf<(() -> Unit)?>(null) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingBtStart.value?.invoke()
        pendingBtStart.value = null
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
                    sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
                }
            }
            else -> {
                if (!handle.isSttAvailable(stt.recognizerComponent)) {
                    sttErrorToast(CaptureError.NO_RECOGNIZER)
                    return
                }
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                if (handle.bluetoothInputDecision(stt.bluetoothInput) == BtInputDecision.ASK_PERMISSION) {
                    handle.markBluetoothPermissionRequested()
                    pendingBtStart.value = { startToggleStt() }
                    btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    return
                }
                startToggleStt()
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
        if (handle.bluetoothInputDecision(stt.bluetoothInput) == BtInputDecision.ASK_PERMISSION) {
            handle.markBluetoothPermissionRequested()
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return false
        }
        val hold = ChatVoiceHoldSession(
            if (useRawCapture()) ru.outsideproarts.oassist.core.speech.CaptureMode.RAW
            else ru.outsideproarts.oassist.core.speech.CaptureMode.STT,
        )
        if (hold.mode == ru.outsideproarts.oassist.core.speech.CaptureMode.RAW) {
            val error = handle.startRaw(bluetoothInput = stt.bluetoothInput)
            if (error != null) {
                sttErrorToast(error)
                return false
            }
            sounds?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
        } else {
            if (!handle.isSttAvailable(stt.recognizerComponent)) {
                sttErrorToast(CaptureError.NO_RECOGNIZER)
                return false
            }
            val error = handle.startStt(
                stt.language,
                sttListener(hold),
                stt.recognizerComponent,
                stt.bluetoothInput,
            )
            if (error != null) {
                sttErrorToast(error)
                return false
            }
            sounds?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
        }
        holdSession = hold
        return true
    }
    fun cancelHold(tooShort: Boolean) {
        val handle = voice ?: return
        val hold = holdSession ?: return
        holdSession = null
        if (hold.cancel()) {
            val wasListening = handle.captureState().value is CaptureState.Listening
            when (hold.mode) {
                ru.outsideproarts.oassist.core.speech.CaptureMode.STT -> handle.cancelStt()
                ru.outsideproarts.oassist.core.speech.CaptureMode.RAW -> handle.cancelRaw()
            }
            if (wasListening) sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
        }
        sttPreview = ""
        if (tooShort) Toast.makeText(context, voiceShortText, Toast.LENGTH_SHORT).show()
    }
    // Отпускание холда: STT — финал придёт колбэком, RAW — забираем WAV.
    fun stopHold() {
        val handle = voice ?: return
        val hold = holdSession ?: return
        holdSession = null
        when (hold.mode) {
            ru.outsideproarts.oassist.core.speech.CaptureMode.STT -> {
                val finalText = hold.release()
                if (finalText != null) sendRecognizedText(finalText)
                else if (hold.captureActive) handle.stopStt()
            }
            ru.outsideproarts.oassist.core.speech.CaptureMode.RAW -> {
                val wasListening = handle.captureState().value is CaptureState.Listening
                val wav = handle.stopRaw()
                if (wasListening) sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
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
        }
    }

    // Фолбэк с гарнитуры mid-capture: route → микрофон при активном захвате.
    // Штатный стоп снимает маршрут уже после Idle — без объявления.
    val voiceRouteFlow = remember {
        voice?.routeState() ?: kotlinx.coroutines.flow.MutableStateFlow(VoiceInputRoute.MICROPHONE)
    }
    val voiceRoute = voiceRouteFlow.collectAsState().value
    var prevVoiceRoute by remember { mutableStateOf(VoiceInputRoute.MICROPHONE) }
    LaunchedEffect(voiceRoute) {
        if (prevVoiceRoute == VoiceInputRoute.BLUETOOTH && voiceRoute == VoiceInputRoute.MICROPHONE &&
            voice?.captureState()?.value is CaptureState.Listening
        ) {
            Toast.makeText(context, micBtFallbackText, Toast.LENGTH_LONG).show()
        }
        prevVoiceRoute = voiceRoute
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
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
        if (showChatInfo) {
            ChatInfoScreen(
                initialTitle = chatTitle,
                kind = chatKind,
                turns = turnStore,
                onBack = { showChatInfo = false },
                onRename = ::renameCurrentChat,
            )
        } else {
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
                    onClick = { uiScope.launch { drawerState.open() } },
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
                        .oassistHeading()
                        .oassistTouchTarget()
                        .clickable { showChatInfo = true },
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
                }
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
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
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
                    input.text.isBlank() -> {
                        actionContent = holdHintText
                        Icons.Default.Mic
                    }
                    else -> {
                        actionContent = sendLabel
                        Icons.AutoMirrored.Filled.Send
                    }
                }
                ChatInputButton(
                    icon = actionIcon,
                    label = if (input.text.isBlank() && !isStreaming && !capturing) {
                        "$micLabel. $holdHintText"
                    } else {
                        actionContent
                    },
                    holdLabel = holdActionLabel,
                    canStartHold = input.text.isBlank() && !isStreaming && !capturing,
                    holdEnabled = !isStreaming && (input.text.isBlank() || capturing),
                    onHoldStart = ::startHold,
                    onHoldRelease = ::stopHold,
                    onHoldCancel = ::cancelHold,
                    voiceActionLabel = if (!isStreaming && (input.text.isBlank() || capturing)) {
                        if (capturing) stopVoiceLabel else startVoiceLabel
                    } else {
                        null
                    },
                    voiceStateLabel = if (!isStreaming && (input.text.isBlank() || capturing)) {
                        if (capturing) listeningText else micIdleLabel
                    } else {
                        null
                    },
                    onClick = {
                        if (isStreaming) {
                            // Стоп: отмена коллекции снимает стрим, паузу подтверждения и HTTP-запрос.
                            // Озвучку тоже гасим (barge-in): недоговорённое не должно дочитываться.
                            // Без job — снимает ожидание сети (см. cancelTurn).
                            cancelTurn()
                            return@ChatInputButton
                        }
                        if (capturing) {
                            toggleSttMic()
                            return@ChatInputButton
                        }
                        if (input.text.isNotBlank()) {
                            val userMsg = input.text.trim()
                            input = TextFieldValue("")
                            sttPreview = ""
                            send(userMsg)
                        } else {
                            toggleSttMic()
                        }
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
                // Удалённый диалог не должен остаться открытым: сброс вида
                // в пустой новый чат, затем удаление из списка.
                resetChat()
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
        work = item.work,
        usage = item.usage,
        onSpeak = onSpeak,
        speakLabel = speakLabel,
        isStreaming = item.isStreaming,
    )
}
