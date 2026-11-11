package ru.outsideproarts.oassist.feature.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.ChatTitler
import ru.outsideproarts.oassist.core.agent.WorkTimelineBuilder
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry
import ru.outsideproarts.oassist.core.model.TtsConfig
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.model.diagnostics.launchDiagnostic
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.provider.isEarlyRetryable
import ru.outsideproarts.oassist.core.speech.AgentSound
import ru.outsideproarts.oassist.core.speech.SoundHandle
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.ui.components.ChatScrollMemory
import ru.outsideproarts.oassist.core.ui.components.ChatScrollRequester

/** Одноразовый тост из VM: строка резолвится в UI в текущей локали Activity. */
sealed interface ShareToast {
    /** Ошибка операции: текст берётся из [ProviderError.userMessage]. */
    data class Error(val error: ProviderError) : ShareToast

    /** Строка из ресурсов; [long] — длительность показа. */
    data class Res(val resId: Int, val long: Boolean = false) : ShareToast
}

/**
 * Собранный в UI запрос хода: локализованные строки и payload не резолвятся
 * в VM. Хранится и для отложенного повтора, когда сеть вернулась.
 */
data class ShareAskRequest(
    val query: String,
    val userMsg: String,
    val modelInput: ModelUserInput,
    val contentDesc: String,
)

/**
 * Сессионное состояние шаринга, переживающее пересоздание Activity (поворот,
 * смена языка/темы, «не сохранять активности»): лента ходов, черновик вопроса
 * и идущий стрим живут здесь и в скоупе сессии, а не в композиции.
 *
 * UI-локальные состояния (диалоги «Сохранить?»/удаления) остаются в
 * композиции ShareFlow — им пересоздание не мешает.
 */
class ShareSessionViewModel internal constructor(private val chatStore: ShareChatStore?) : ViewModel() {
    constructor() : this(ChatHandle.get()?.let(::RepositoryShareChatStore))

    /** Экран: список диалогов или открытый разговор. */
    var inConversation by mutableStateOf(false)
    var chatId by mutableStateOf<String?>(null)
    var chatTitle by mutableStateOf("")
    var titleLocked by mutableStateOf(false)

    /** Ходы накапливаются в памяти — источник истины для истории и записи в БД.
     *  Каждый ход несёт id своей строки (ручка удаления/отката). */
    val turns = mutableStateListOf<StoredTurnEntry>()
    var diagnosticOperations = mutableListOf<String>()

    /**
     * Задания прокрутки ленты: ставятся при открытии/сбросе разговора и отправке
     * вопроса, исполняются UI (см. rememberChatListScroll в ShareConversation).
     */
    val scrollRequester = ChatScrollRequester()
    var autoSave by mutableStateOf(true)
    var images by mutableStateOf<List<ImagePart>>(emptyList())
        private set
    var payloadReady by mutableStateOf(false)
        private set
    var payloadLoadFailed by mutableStateOf(false)
        private set
    var isChangingConversation by mutableStateOf(false)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set
    private var isClosing = false
    val canSend: Boolean get() = payloadReady && !isClosing && !isChangingConversation && !isSaving && !saveFailed && !isStreaming
    private var payloadJob: Job? = null
    private var streamJob: Job? = null
    private var changeJob: Job? = null
    /** Хвост цепочки записи: дозаписи ходов и стирания идут строго по очереди. */
    private var lastWrite: Job? = null
    private var writesInFlight = 0
    private var titleJob: Job? = null
    private var savedTurnCount = 0

    /** Owned by the VM so Activity recreation cannot restart or truncate the payload load. */
    fun loadPayload(load: suspend () -> List<ImagePart>) {
        if (isClosing || payloadJob?.isActive == true || payloadReady) return
        payloadLoadFailed = false
        payloadJob = scope.launch {
            try {
                images = load().toList()
                payloadReady = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                payloadLoadFailed = true
                _toasts.tryEmit(ShareToast.Error(ProviderError.from(error)))
            } finally {
                payloadJob = null
            }
        }
    }

    private fun changeConversation(discard: Boolean = false, change: suspend () -> Unit) {
        if (isClosing) return
        val previous = changeJob
        previous?.cancel()
        streamJob?.cancel()
        titleJob?.cancel()
        pendingRetry = null
        isChangingConversation = true
        changeJob = scope.launch {
            try {
                previous?.join()
                streamJob?.join()
                isStreaming = false
                titleJob?.join()
                lastWrite?.join()
                if (saveFailed && !discard) return@launch
                change()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _toasts.tryEmit(ShareToast.Error(ProviderError.from(error)))
            } finally {
                // A newer transition owns the busy flag after cancellation.
                if (coroutineContext[Job]?.isActive == true) isChangingConversation = false
            }
        }
    }

    private fun resetConversation() {
        providerNotice = null
        diagnosticOperations = mutableListOf()
        chatId = null
        chatTitle = ""
        titleLocked = false
        turns.clear()
        savedTurnCount = 0
        saveFailed = false
        activeUserMsg = null
        workEvents = emptyList()
        response = ""
        turnUsage = null
        isStreaming = false
        question = TextFieldValue("")
        // Свежий разговор всегда открывается с конца ленты.
        scrollRequester.scrollToEnd()
    }

    var question by mutableStateOf(TextFieldValue(""))
    var activeUserMsg by mutableStateOf<String?>(null)
    var workEvents by mutableStateOf<List<WorkEvent>>(emptyList())
    var response by mutableStateOf("")
    var turnUsage by mutableStateOf<ProviderUsage?>(null)
    var isStreaming by mutableStateOf(false)
    var providerNotice by mutableStateOf<ChatChunk.ProviderNotice.Kind?>(null)
        private set

    /** Запрос, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    private var pendingRetry by mutableStateOf<ShareAskRequest?>(null)

    /** Восстановление из saved state после смерти процесса уже выполнено. */
    private var saveRestored = false

    /**
     * Скоуп сессии шаринга: не гибнет при пересоздании Activity, отменяется
     * только вместе с ViewModel. Свой, не viewModelScope — без зависимости
     * от lifecycle-viewmodel-ktx.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _toasts = MutableSharedFlow<ShareToast>(extraBufferCapacity = 8)

    /**
     * Тосты — транзиентные уведомления. Допущение: событие, эмитированное в
     * окне между пересозданием Activity и новой подпиской коллектора, теряется
     * (replay = 0); потеря ошибочного тоста в этом окне допустима — состояние
     * ошибки видно и в UI.
     */
    val toasts: SharedFlow<ShareToast> = _toasts

    init {
        scope.launch {
            autoSave = runCatching {
                AgentFactory.get().modeConfig(AssistMode.SHARE).effectiveAutoSaveChats()
            }.getOrDefault(true)
        }
        // Сеть вернулась — автоматически повторяем отложенный ход.
        scope.launch {
            var wasOnline = NetworkHandle.get()?.isOnline?.value ?: true
            NetworkHandle.get()?.isOnline?.collect { online ->
                if (online && !wasOnline) {
                    val retry = pendingRetry
                    if (retry != null && !isStreaming) {
                        _toasts.tryEmit(ShareToast.Res(ru.outsideproarts.oassist.core.ui.R.string.net_repeating))
                        ask(retry)
                    }
                }
                wasOnline = online
            }
        }
    }

    override fun onCleared() {
        isClosing = true
        pendingRetry = null
        payloadJob?.cancel()
        changeJob?.cancel()
        titleJob?.cancel()
        streamJob?.cancel()
        // The terminal callback can still enqueue a write while the stream is cancelling.
        scope.launch {
            try {
                payloadJob?.join()
                changeJob?.join()
                streamJob?.join()
                titleJob?.join()
                lastWrite?.join()
            } finally {
                scope.cancel()
            }
        }
    }

    /** Вход в разговор с сохранённым диалогом: история читается из БД. */
    fun openChat(summary: ChatSummary) {
        changeConversation {
            val loaded = chatStore?.loadTurnEntries(summary.id).orEmpty()
            coroutineContext.ensureActive()
            resetConversation()
            chatId = summary.id
            chatTitle = summary.title
            titleLocked = true
            inConversation = true
            turns.addAll(loaded)
            savedTurnCount = loaded.size
            // Позиция восстанавливается из памяти; для незнакомого диалога — в конец.
            scrollRequester.scrollTo(ChatScrollMemory.restore(summary.id))
        }
    }

    /** Новый незакреплённый разговор. */
    fun newChat() {
        changeConversation {
            resetConversation()
            inConversation = true
        }
    }

    fun renameChat(summary: ChatSummary, name: String) {
        chatStore?.let { repo -> scope.launch { repo.renameChat(summary.id, name) } }
        if (summary.id == chatId) {
            chatTitle = name
            titleLocked = true
        }
    }

    fun deleteChatFromList(summary: ChatSummary) {
        changeConversation(discard = summary.id == chatId) {
            ChatScrollMemory.remove(summary.id)
            chatStore?.deleteChat(summary.id)
            if (summary.id == chatId) resetConversation()
        }
    }

    /** Уход со экрана разговора к списку (снимок pending-диалога живёт в UI). */
    fun leaveConversation() {
        changeConversation {
            providerNotice = null
            activeUserMsg = null
            inConversation = false
        }
    }

    /** Сохранить несохранённый разговор в список чатов. */
    fun saveChat() {
        if (!isClosing && !isChangingConversation) saveTurns()
    }

    /**
     * Удалить текущий диалог: удалённый разговор не должен остаться открытым —
     * состояние чистится до «Нового чата», панель полученного остаётся.
     */
    fun deleteCurrentChat() {
        changeConversation(discard = true) {
            chatId?.let {
                ChatScrollMemory.remove(it)
                chatStore?.deleteChat(it)
            }
            resetConversation()
        }
    }

    /** Ход ждёт сети: повтор запустится автоматически из наблюдателя сети. */
    fun deferAsk(request: ShareAskRequest) {
        if (canSend) pendingRetry = request
    }

    /**
     * Восстановление после смерти процесса: VM с историей погиб, но id открытого
     * разговора пережил в saved state. Переоткрываем экран, ходы дочитываются
     * из БД. Выполняется один раз на VM: при пересоздании с живым VM (смена
     * языка/темы) состояние не трогается.
     */
    fun restoreAfterProcessDeath(wasInConversation: Boolean, savedChatId: String?) {
        if (saveRestored) return
        saveRestored = true
        if (!wasInConversation) return
        inConversation = true
        val id = savedChatId?.takeIf { it.isNotEmpty() } ?: return
        changeConversation {
            val repo = chatStore ?: return@changeConversation
            val title = repo.title(id)
            val loaded = repo.loadTurnEntries(id)
            coroutineContext.ensureActive()
            resetConversation()
            chatId = id
            titleLocked = true
            chatTitle = title
            inConversation = true
            turns.addAll(loaded)
            savedTurnCount = loaded.size
            // Позиция восстанавливается из памяти; для незнакомого диалога — в конец.
            scrollRequester.scrollTo(ChatScrollMemory.restore(id))
        }
    }

    fun ask(request: ShareAskRequest) {
        val query = request.query
        if (query.isBlank() || !canSend) return
        val sentImages = images.toList()
        pendingRetry = null
        isStreaming = true
        SpeechHandle.get()?.stop()
        val userMsg = request.userMsg
        val modelInput = request.modelInput
        val contentDesc = request.contentDesc
        activeUserMsg = userMsg
        // Свой вопрос всегда показываем снизу: ленту ждём в конце.
        scrollRequester.scrollToEnd()
        val history = turns.flatMap { it.turn.modelMessages() }
        val timeline = WorkTimelineBuilder()
        workEvents = emptyList()
        response = ""
        turnUsage = null
        var turnTranscript: List<ChatMessage> = emptyList()
        providerNotice = null
        val diagnosticId = Diagnostics.newOperationId()
        diagnosticOperations.add(diagnosticId)
        val diagnosticChatId = chatId
        val diagnosticRepo = chatStore
        val diagnosticGeneration = diagnosticChatId?.let { diagnosticRepo?.diagnosticGeneration(it) }
        var turnRecorded = false
        fun recordTurn() {
            if (turnRecorded) return
            turnRecorded = true
            if (timeline.isEmpty && turnTranscript.isEmpty()) return
            val stored = StoredTurnEntry(
                id = java.util.UUID.randomUUID().toString(),
                turn = StoredTurn(
                    diagnosticOperationId = diagnosticId,
                    userText = userMsg,
                    modelUserInput = modelInput,
                    userImages = sentImages,
                    work = timeline.work,
                    answer = timeline.answerText,
                    usage = timeline.usage,
                    turn = turnTranscript,
                ),
            )
            turns.add(stored)
            activeUserMsg = null
            persistTurn(stored, nameSeed = query)
        }
        streamJob = scope.launchDiagnostic("SHARE", diagnosticId) launch@{
            try {
                if (diagnosticChatId != null && diagnosticRepo != null && diagnosticGeneration != null) {
                    diagnosticRepo.bindDiagnosticOperation(diagnosticChatId, diagnosticId, diagnosticGeneration)
                }
                val tts = runCatching {
                    AgentFactory.get().modeConfig(AssistMode.SHARE).effectiveTts()
                }.getOrElse { TtsConfig.DEFAULT }
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
                            images = sentImages.ifEmpty { null },
                            onTurnMessages = { turn -> turnTranscript = turn },
                        ),
                        agent = agent,
                    )
                } catch (e: CancellationException) {
                    isStreaming = false
                    throw e
                } catch (e: Throwable) {
                    // Нет провайдера/модели — честное сообщение вместо падения.
                    isStreaming = false
                    activeUserMsg = null
                    val failure = ProviderError.from(e)
                    sounds?.playIfEnabled(AgentSound.ERROR, soundsOn)
                    _toasts.tryEmit(ShareToast.Error(failure))
                    return@launch
                }
                val error = try {
                    session.chunks.collectSafe { chunk ->
                        coroutineContext.ensureActive()
                        when (chunk) {
                            is ChatChunk.ProviderNotice -> providerNotice = chunk.kind
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
                                    _toasts.tryEmit(
                                        ShareToast.Res(
                                            ru.outsideproarts.oassist.core.provider.R.string.provider_stream_truncated,
                                            long = true,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                }
                if (error != null) {
                    val net = NetworkHandle.get()
                    if (net != null && !net.isOnline.value && error.isEarlyRetryable() &&
                        timeline.isEmpty
                    ) {
                        // Упали до первого чанка без сети: ждём фронт online.
                        pendingRetry = request
                        activeUserMsg = null
                        _toasts.tryEmit(
                            ShareToast.Res(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry, long = true),
                        )
                    } else {
                        sounds?.playIfEnabled(AgentSound.ERROR, soundsOn)
                        _toasts.tryEmit(ShareToast.Error(error))
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                activeUserMsg = null
                _toasts.tryEmit(ShareToast.Error(ProviderError.from(error)))
            } finally {
                isStreaming = false
                recordTurn()
            }
        }
    }

    /** Ход в БД: в сохранённый диалог, при autoSave — создать новый, иначе в память. */
    private fun persistTurn(stored: StoredTurnEntry, nameSeed: String) {
        if (chatTitle.isBlank()) chatTitle = ChatTitler.placeholder(nameSeed)
        if (chatId != null || autoSave) saveTurns()
        if (turns.size == 1 && !isChangingConversation && !isClosing) generateTitle(nameSeed, stored)
    }

    /**
     * Единая цепочка записи: дозаписи ходов и стирания идут строго по очереди —
     * стирание выполняется после незаписанных ходов и до новых, поэтому счётчик
     * сохранённых ходов и БД не расходятся с лентой. [reportFailure] — для
     * дозаписей (см. [saveFailed] и «Повторить»); стирания сообщают ошибку тостом.
     */
    private fun enqueueWrite(reportFailure: Boolean = true, op: suspend () -> Unit) {
        writesInFlight++
        isSaving = true
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            try {
                op()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (reportFailure) saveFailed = true
                _toasts.tryEmit(ShareToast.Error(ProviderError.from(error)))
            } finally {
                writesInFlight--
                if (writesInFlight == 0) isSaving = false
            }
        }
    }

    /** Navigation waits for this writer; successful prefixes are never appended twice on retry. */
    private fun saveTurns() {
        if (turns.isEmpty()) return
        val repo = chatStore ?: return
        saveFailed = false
        enqueueWrite {
            if (turns.isEmpty()) return@enqueueWrite
            val id = chatId ?: repo.createChat(
                AssistMode.SHARE,
                chatTitle.ifBlank { ChatTitler.placeholder(turns.first().turn.userText) },
            ).also { chatId = it }
            diagnosticOperations.toList().forEach { repo.bindDiagnosticOperation(id, it) }
            while (savedTurnCount < turns.size) {
                val entry = turns[savedTurnCount]
                repo.appendTurn(id, entry.turn, entry.id)
                savedTurnCount++
            }
        }
    }

    /**
     * Удалить сообщение (ход целиком, с полотном «Ход работы»): лента и БД —
     * в общей цепочке записи, память меняется после успешного стирания.
     */
    fun deleteTurnMessage(turnId: String) = removeTurns(turnId, afterOnly = false)

    /**
     * Откатиться к сообщению: выбранный ход остаётся последним, все следующие
     * стираются безвозвратно (лента и БД). История для модели продолжается
     * с выбранного хода.
     */
    fun rollbackToTurn(turnId: String) = removeTurns(turnId, afterOnly = true)

    private fun removeTurns(turnId: String, afterOnly: Boolean) {
        if (isClosing || isChangingConversation) return
        enqueueWrite(reportFailure = false) {
            val index = turns.indexOfFirst { it.id == turnId }
            if (index < 0) return@enqueueWrite
            val from = if (afterOnly) index + 1 else index
            val until = if (afterOnly) turns.size else index + 1
            if (from >= until) return@enqueueWrite
            // Сотрутся только сохранённые ходы: незаписанные исчезнут и так.
            val savedDoomed = (from until until).count { it < savedTurnCount }
            if (savedDoomed > 0) {
                chatId?.let { id ->
                    if (afterOnly) chatStore?.deleteTurnsAfter(id, turnId)
                    else chatStore?.deleteTurn(id, turnId)
                }
            }
            for (i in until - 1 downTo from) turns.removeAt(i)
            savedTurnCount -= savedDoomed
        }
    }

    private fun generateTitle(nameSeed: String, expectedFirstTurn: StoredTurnEntry) {
        if (titleLocked || turns.firstOrNull() !== expectedFirstTurn) return
        titleJob = scope.launch(
            DiagnosticContext(
                expectedFirstTurn.turn.diagnosticOperationId ?: Diagnostics.newOperationId(),
            ),
        ) {
            lastWrite?.join()
            if (saveFailed) return@launch
            if (titleLocked || turns.firstOrNull() !== expectedFirstTurn) return@launch
            val generated = runCatching {
                ChatTitler.generateTitle(AssistMode.SHARE, nameSeed, AgentFactory.get())
            }.getOrNull() ?: return@launch
            if (!titleLocked && turns.firstOrNull() === expectedFirstTurn) {
                chatTitle = generated
                val id = chatId
                if (id != null) chatStore?.renameChat(id, generated)
            }
        }
    }
}
