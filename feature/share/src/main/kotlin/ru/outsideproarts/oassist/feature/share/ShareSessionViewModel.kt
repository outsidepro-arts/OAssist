package ru.outsideproarts.oassist.feature.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.ChatTitler
import ru.outsideproarts.oassist.core.agent.WorkTimelineBuilder
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.CURRENT_TURN_ID
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.ContextData
import ru.outsideproarts.oassist.core.model.ContextSource
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.model.PinChange
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry
import ru.outsideproarts.oassist.core.model.TtsConfig
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.model.parseMessageId
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
    /** Голосовое приложение (hold-to-talk RAW): ход без текста, только аудио. */
    val audio: ru.outsideproarts.oassist.core.model.AudioPart? = null,
)

/** Область стирания ходов: один ход, всё после хода, ход вместе со всеми последующими. */
internal enum class TurnRemoval {
    ONE,
    AFTER,
    FROM,
}

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
    // Отправка блокируется только идущим стримом или сменой разговора — как в Chat:
    // идущая или сбойная запись не должна останавливать диалог (очередь дозапишет).
    val canSend: Boolean get() = payloadReady && !isClosing && !isChangingConversation && !isStreaming
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
        pendingApproval = null
        // Смена разговора отменяет и отложенные повторы генерации: запоздавшее
        // стирание не должно запускать ход в другом разговоре.
        pendingRegenerates.clear()
        isChangingConversation = true
        changeJob = scope.launch {
            try {
                previous?.join()
                streamJob?.join()
                isStreaming = false
                titleJob?.join()
                lastWrite?.join()
                if (saveFailed && !discard) {
                    // Незаписанные изменения не должны потеряться при смене
                    // разговора: смена ждёт явного «Повторить» (не молча).
                    _toasts.tryEmit(ShareToast.Res(ru.outsideproarts.oassist.feature.share.R.string.share_retry_first))
                    return@launch
                }
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
        lastAskRequest = null
        workEvents = emptyList()
        response = ""
        turnUsage = null
        isStreaming = false
        pendingApproval = null
        failedRemoval = null
        pendingRegenerates.clear()
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

    /**
     * Запрос подтверждения действия, ожидающий решения пользователя — как в Chat:
     * Share показывает общий ApprovalDialog, а не отклоняет молча (иначе
     * закрепления и записи памяти в Share принципиально невыполнимы).
     */
    var pendingApproval by mutableStateOf<Pair<ApprovalRequest, AgentLoop>?>(null)

    /** Решение по запросу подтверждения (общий диалог ApprovalDialog). */
    fun resolveApproval(decision: ApprovalDecision) {
        val (request, agent) = pendingApproval ?: return
        pendingApproval = null
        if (decision == ApprovalDecision.DENY || decision == ApprovalDecision.ALWAYS_DENY) {
            SoundHandle.get()?.playIfEnabled(AgentSound.PERMISSION_DENIED, lastSoundsOn)
        }
        agent.resolveApproval(request.callId, decision)
    }

    /** Стоп: отмена текущего хода и отложенного запроса — как в Chat. */
    fun cancelTurn() {
        pendingRetry = null
        pendingApproval = null
        val hadJob = streamJob != null
        streamJob?.cancel()
        SpeechHandle.get()?.stop()
        if (!hadJob && isStreaming) {
            // Отмена в ожидании сети: висящего стрима нет, состояние гасим сами.
            isStreaming = false
        }
    }

    /** Запрос, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    private var pendingRetry by mutableStateOf<ShareAskRequest?>(null)

    /**
     * Запрос последнего хода (идущего или не записанного после ошибки/стопа) —
     * ручка «Повторить» на активном сообщении без записанного хода.
     */
    private var lastAskRequest: ShareAskRequest? = null

    /** Флаг звука режима последнего хода: звук решений диалога вне стрима. */
    private var lastSoundsOn = true

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
        // Сеть вернулась — автоматически повторяем отложенный ход (как в Chat:
        // wasOnline = false при старте — повтор сработает и по текущему состоянию).
        scope.launch {
            var wasOnline = false
            NetworkHandle.get()?.isOnline?.collect { online ->
                if (online && !wasOnline) {
                    val retry = pendingRetry
                    if (retry != null && streamJob == null && isStreaming) {
                        _toasts.tryEmit(ShareToast.Res(ru.outsideproarts.oassist.core.ui.R.string.net_repeating))
                        retryAsk(retry)
                    }
                }
                wasOnline = online
            }
        }
    }

    override fun onCleared() {
        isClosing = true
        pendingRetry = null
        pendingApproval = null
        // «Разрешено на сессию» SHARE не переживает закрытие — как в Chat.
        runCatching { AgentFactory.get().clearSessionApprovals(AssistMode.SHARE) }
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

    /** Сбойное стирание, ожидающее повтора («Повторить»). */
    private var failedRemoval: RemovalPlan? = null

    /**
     * Подтверждённый диапазон стирания: фиксируется в момент действия, повтор
     * сбойного задания исполняет тот же снимок и не трогает более новые ходы.
     */
    private class RemovalPlan(
        val turnId: String,
        val removal: TurnRemoval,
        val doomedIds: Set<String>,
        val killTarget: Boolean,
    )

    /**
     * Повторы генерации, отложенные сбойным стиранием по id хода: завершатся
     * после успешного «Повторить запись» — подтверждённое действие не должно
     * выполняться наполовину.
     */
    private val pendingRegenerates = mutableMapOf<String, ShareAskRequest>()

    /**
     * «Повторить»: повтор сбойного стирания и сбойных дозаписей. Кнопка не
     * должна маскировать потерю незаписанного хода — дозаписи повторяются
     * всегда ([saveChat]), даже когда стирался другой ход.
     */
    fun retryWrites() {
        val removal = failedRemoval
        if (removal != null && !isClosing && !isChangingConversation) {
            failedRemoval = null
            // Повтор исполняет снимок диапазона момента действия — более новые
            // ходы, появившиеся после подтверждения, не затрагиваются.
            removeTurns(removal, onFinished = { continuePendingRegenerate(removal.turnId) })
        }
        saveChat()
    }

    /**
     * Генерация, отложенная сбойным стиранием, стартует после успешного повтора.
     * Если к этому моменту идёт другая генерация, повтор не запускается —
     * пользователь видит тост, действие не теряется молча.
     */
    private fun continuePendingRegenerate(removalTurnId: String) {
        val request = pendingRegenerates.remove(removalTurnId) ?: return
        if (isClosing || isChangingConversation) return
        if (isStreaming) {
            _toasts.tryEmit(
                ShareToast.Res(
                    ru.outsideproarts.oassist.feature.share.R.string.share_regenerate_skipped,
                    long = true,
                ),
            )
            return
        }
        retryAsk(request)
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

    /**
     * Ход ждёт сети: запрос сразу виден в ленте ожидания (как в Chat), звук
     * отправки играет в момент отправки; повтор запустится автоматически из
     * наблюдателя сети.
     */
    fun deferAsk(request: ShareAskRequest) {
        if (!canSend) return
        activeUserMsg = request.userMsg
        lastAskRequest = request
        isStreaming = true
        pendingRetry = request
        _toasts.tryEmit(ShareToast.Res(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry, long = true))
        scope.launch {
            val soundsOn = runCatching {
                AgentFactory.get().modeConfig(AssistMode.SHARE).effectiveSoundEnabled()
            }.getOrDefault(true)
            lastSoundsOn = soundsOn
            SoundHandle.get()?.playIfEnabled(AgentSound.MSG_SEND, soundsOn)
        }
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

    fun ask(request: ShareAskRequest, announceSound: Boolean = true) {
        val query = request.query
        // Голосовой ход без текста допустим (как в Chat).
        if ((query.isBlank() && request.audio == null) || !canSend) return
        startAsk(request, announceSound)
    }

    /**
     * Повтор отложенного хода: минует guard'ы [canSend] — ожидание уже «идёт»
     * (как прямой запуск хода из Chat после фронта сети).
     */
    private fun retryAsk(request: ShareAskRequest) {
        if (isClosing || isChangingConversation) return
        startAsk(request, announceSound = false)
    }

    private fun startAsk(request: ShareAskRequest, announceSound: Boolean) {
        val query = request.query
        val sentImages = images.toList()
        pendingRetry = null
        lastAskRequest = request
        isStreaming = true
        SpeechHandle.get()?.stop()
        val userMsg = request.userMsg
        val modelInput = request.modelInput
        val contentDesc = request.contentDesc
        activeUserMsg = userMsg
        // Свой вопрос всегда показываем снизу: ленту ждём в конце.
        scrollRequester.scrollToEnd()
        val history = turns.flatMap { it.modelMessages() }
        val timeline = WorkTimelineBuilder()
        workEvents = emptyList()
        response = ""
        turnUsage = null
        var turnTranscript: List<ChatMessage> = emptyList()
        // Закрепления текущего хода применяются к ходу при записи; закрепления
        // прошлых ходов применяются к памяти и БД сразу (см. applyPinChange).
        val turnPins = mutableListOf<PinChange>()
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
                    userAudio = request.audio,
                    modelUserInput = modelInput,
                    userImages = sentImages,
                    work = timeline.work,
                    answer = timeline.answerText,
                    usage = timeline.usage,
                    turn = turnTranscript,
                ).withPinChanges(turnPins),
            )
            turns.add(stored)
            activeUserMsg = null
            // Голосовой ход без текста называется по ответу — как в Chat.
            persistTurn(stored, nameSeed = query.ifBlank { userMsg }.ifBlank { stored.turn.answer })
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
                lastSoundsOn = soundsOn
                val sounds = SoundHandle.get()
                // Звук отправки — в момент отправки: авто-повтор хода не звучит (как в Chat).
                if (announceSound) sounds?.playIfEnabled(AgentSound.MSG_SEND, soundsOn)
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
                            // Общий контент — данными контекста, один раз на запрос:
                            // дубль из {shared_content} шаблона дедуплицируется, а без
                            // плейсхолдера доставка всё равно гарантирована.
                            promptContext = (
                                prompt.context + contentDesc.ifBlank { null }
                                    ?.let { listOf(ContextData(ContextSource.SHARED_CONTENT, it)) }
                                    .orEmpty()
                                ).distinct(),
                            modelUserInput = modelInput,
                            history = history,
                            userMessage = userMsg,
                            audio = request.audio,
                            images = sentImages.ifEmpty { null },
                            onTurnMessages = { turn -> turnTranscript = turn },
                            onPinChange = { change -> applyPinChange(change, turnPins) },
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
                            // Подтверждения — как в Chat: запрос пользователю,
                            // а не авто-отказ (см. pendingApproval).
                            is ChatChunk.ApprovalNeeded -> {
                                sounds?.playIfEnabled(AgentSound.PERMISSION_REQUEST, soundsOn)
                                pendingApproval = chunk.request to session.agent
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
                        // Вопрос остаётся видимым в ленте ожидания — как в Chat.
                        pendingRetry = request
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
                // Ожидание сети после раннего обрыва держит состояние стрима
                // (как восстановление «ждущего» пузыря в Chat); job сбрасывается,
                // чтобы «Стоп» и авто-повтор видели завершённый ход.
                streamJob = null
                isStreaming = pendingRetry != null
                pendingApproval = null
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
     * Закрепление из `message_pin`/`message_unpin`: сообщения текущего хода
     * применяются к ходу при записи ([StoredTurn.withPinChanges]), прошлых ходов —
     * сразу к ленте (бюджеты следующего хода) и к БД в общей очереди записи.
     */
    private suspend fun applyPinChange(change: PinChange, currentPins: MutableList<PinChange>) {
        val ref = parseMessageId(change.messageId) ?: return
        if (ref.turnId == CURRENT_TURN_ID) {
            currentPins.add(change)
            return
        }
        val index = turns.indexOfFirst { it.id == ref.turnId }
        if (index >= 0) {
            turns[index] = turns[index].copy(
                turn = turns[index].turn.withMessagePin(ref.index, change.pinned, change.note),
            )
        }
        val id = chatId ?: return
        enqueueWrite(reportFailure = false) {
            chatStore?.setPin(id, ref.turnId, ref.index, change.pinned, change.note)
        }
    }

    /**
     * Единая цепочка записи: дозаписи ходов и стирания идут строго по очереди —
     * стирание выполняется после незаписанных ходов и до новых, поэтому счётчик
     * сохранённых ходов и БД не расходятся с лентой. [reportFailure] переводит
     * запись в состояние «Повторить» (см. [saveFailed]); [onFailure] — для
     * повторяемых операций (сбойное стирание, см. [retryWrites]).
     */
    private fun enqueueWrite(
        reportFailure: Boolean = true,
        onFailure: (() -> Unit)? = null,
        op: suspend () -> Unit,
    ): Job {
        writesInFlight++
        isSaving = true
        val previous = lastWrite
        val job = scope.launch {
            previous?.join()
            try {
                op()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (reportFailure) saveFailed = true
                onFailure?.invoke()
                _toasts.tryEmit(ShareToast.Error(ProviderError.from(error)))
            } finally {
                writesInFlight--
                if (writesInFlight == 0) isSaving = false
            }
        }
        lastWrite = job
        return job
    }

    /** Navigation waits for this writer; successful prefixes are never appended twice on retry. */
    private fun saveTurns() {
        if (turns.isEmpty()) return
        val repo = chatStore ?: return
        // Сбойное стирание продолжает требовать «Повторить запись»: успешная
        // дозапись другого хода не должна прятать подтверждённое действие.
        if (failedRemoval == null) saveFailed = false
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
    fun deleteTurnMessage(turnId: String) {
        removeTurns(turnId, TurnRemoval.ONE)
    }

    /**
     * Откатиться к сообщению: выбранный ход остаётся последним, все следующие
     * стираются безвозвратно (лента и БД). История для модели продолжается
     * с выбранного хода.
     */
    fun rollbackToTurn(turnId: String) {
        removeTurns(turnId, TurnRemoval.AFTER)
    }

    /**
     * Повторить генерацию по сообщению: выбранный ход и все последующие удаляются
     * (лента и БД), затем запрос уходит заново с вводом выбранного сообщения.
     * Ход без записанного ответа (ошибка/стоп) перезапускается тем же способом.
     * Повтор стартует после успешного стирания — сразу или после «Повторить
     * запись», если сначала сбой записи: история следующего запроса не должна
     * содержать стёртый ход. Стоп и смена разговора отменяют и генерацию;
     * подтверждённое стирание к тому моменту уже не отменяется.
     */
    fun regenerateTurn(turnId: String, request: ShareAskRequest) {
        if (isClosing || isChangingConversation || isStreaming) return
        if (request.query.isBlank() && request.audio == null) return
        pendingRetry = null
        pendingRegenerates[turnId] = request
        // До завершения стирания ход считается идущим: отправка заблокирована
        // (гонка за историю), действия сообщений скрыты, «Стоп» отменяет повтор.
        isStreaming = true
        // LAZY + start: streamJob получает Job до запуска тела — иначе
        // синхронно отработавший повтор затёр бы его своим Job (Main.immediate).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var removed = false
                removeTurns(turnId, TurnRemoval.FROM, onFinished = { removed = true })?.join()
                isStreaming = false
                streamJob = null
                if (removed) {
                    pendingRegenerates.remove(turnId)
                    retryAsk(request)
                }
            } catch (cancel: CancellationException) {
                pendingRegenerates.remove(turnId)
                isStreaming = false
                streamJob = null
                throw cancel
            }
        }
        streamJob = job
        job.start()
    }

    /**
     * Повторить генерацию по активному сообщению (записанного хода нет: ошибка
     * генерации или стоп) — тот же запрос уходит заново без стирания.
     */
    fun regenerateActive() {
        if (isClosing || isChangingConversation || isStreaming) return
        val request = lastAskRequest ?: return
        retryAsk(request)
    }

    private fun removeTurns(turnId: String, removal: TurnRemoval, onFinished: (() -> Unit)? = null): Job? {
        // Подтверждённый диапазон фиксируется в момент действия: повтор сбойного
        // задания записи не трогает ходы, появившиеся позже, включая идущий стрим.
        val anchorIndex = turns.indexOfFirst { it.id == turnId }
        val following = when {
            removal == TurnRemoval.ONE -> emptySet()
            anchorIndex < 0 -> emptySet()
            else -> turns.drop(anchorIndex + 1).map { it.id }.toSet()
        }
        val killTarget = removal != TurnRemoval.AFTER
        return removeTurns(
            RemovalPlan(turnId, removal, if (killTarget) following + turnId else following, killTarget),
            onFinished,
        )
    }

    private fun removeTurns(plan: RemovalPlan, onFinished: (() -> Unit)? = null): Job? {
        if (isClosing || isChangingConversation) return null
        val turnId = plan.turnId
        val removal = plan.removal
        val killTarget = plan.killTarget
        val doomedIds = plan.doomedIds
        return enqueueWrite(reportFailure = true, onFailure = { failedRemoval = plan }) {
            val doomedNow = turns.filter { it.id in doomedIds }
            if (doomedNow.isNotEmpty()) {
                // Сотрутся только сохранённые ходы: незаписанные исчезнут и так.
                val savedDoomed = doomedNow.count { entry -> turns.indexOfFirst { it === entry } < savedTurnCount }
                if (savedDoomed > 0) {
                    chatId?.let { id ->
                        val indexNow = turns.indexOfFirst { it.id == turnId }
                        val followingNow = if (indexNow >= 0) turns.drop(indexNow + 1) else emptyList()
                        if (removal != TurnRemoval.ONE && indexNow >= 0 && followingNow.all { it.id in doomedIds }) {
                            // Хвост после якоря не изменился — стирание одной операцией.
                            chatStore?.deleteTurnsAfter(id, turnId)
                            if (killTarget) chatStore?.deleteTurn(id, turnId)
                        } else {
                            // Появились более новые ходы: стираем только подтверждённый набор.
                            doomedNow.forEach { chatStore?.deleteTurn(id, it.id) }
                        }
                    }
                }
                turns.removeAll { it.id in doomedIds }
                savedTurnCount -= savedDoomed
            }
            onFinished?.invoke()
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
                ChatTitler.generateTitle(
                    AssistMode.SHARE,
                    nameSeed,
                    AgentFactory.get(),
                    // Аудио-ход без текста называется по ответу — как в Chat.
                    firstAnswer = expectedFirstTurn.turn.answer,
                )
            }.getOrNull() ?: return@launch
            if (!titleLocked && turns.firstOrNull() === expectedFirstTurn) {
                chatTitle = generated
                val id = chatId
                if (id != null) chatStore?.renameChat(id, generated)
            }
        }
    }
}
