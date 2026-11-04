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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
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
class ShareSessionViewModel : ViewModel() {

    /** Экран: список диалогов или открытый разговор. */
    var inConversation by mutableStateOf(false)
    var chatId by mutableStateOf<String?>(null)
    var chatTitle by mutableStateOf("")
    var titleLocked by mutableStateOf(false)

    /** Ходы накапливаются в памяти — источник истины для истории и записи в БД. */
    val turns = mutableStateListOf<StoredTurn>()
    var diagnosticOperations = mutableListOf<String>()
    var autoSave by mutableStateOf(true)
    var images by mutableStateOf<List<ImagePart>>(emptyList())

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
        scope.cancel()
    }

    /** Вход в разговор с сохранённым диалогом: история читается из БД. */
    fun openChat(summary: ChatSummary) {
        providerNotice = null
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
    }

    /** Новый незакреплённый разговор. */
    fun newChat() {
        providerNotice = null
        diagnosticOperations = mutableListOf()
        chatId = null
        chatTitle = ""
        titleLocked = false
        turns.clear()
        activeUserMsg = null
        inConversation = true
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
        if (summary.id == chatId) chatId = null
    }

    /** Уход со экрана разговора к списку (снимок pending-диалога живёт в UI). */
    fun leaveConversation() {
        providerNotice = null
        activeUserMsg = null
        inConversation = false
    }

    /** Сохранить несохранённый разговор в список чатов. */
    fun saveChat() {
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
    }

    /**
     * Удалить текущий диалог: удалённый разговор не должен остаться открытым —
     * состояние чистится до «Нового чата», панель полученного остаётся.
     */
    fun deleteCurrentChat() {
        providerNotice = null
        val id = chatId
        chatId = null
        chatTitle = ""
        titleLocked = false
        turns.clear()
        activeUserMsg = null
        workEvents = emptyList()
        response = ""
        turnUsage = null
        pendingRetry = null
        if (id != null) {
            val repo = ChatHandle.get()
            if (repo != null) {
                scope.launch { repo.deleteChat(id) }
            }
        }
    }

    /** Ход ждёт сети: повтор запустится автоматически из наблюдателя сети. */
    fun deferAsk(request: ShareAskRequest) {
        pendingRetry = request
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
        chatId = id
        titleLocked = true
        scope.launch {
            val repo = ChatHandle.get() ?: return@launch
            chatTitle = repo.observeChats().first().find { it.id == id }?.title.orEmpty()
            turns.clear()
            turns.addAll(repo.loadTurns(id))
        }
    }

    fun ask(request: ShareAskRequest) {
        val query = request.query
        if (query.isBlank() || isStreaming) return
        pendingRetry = null
        isStreaming = true
        SpeechHandle.get()?.stop()
        val userMsg = request.userMsg
        val modelInput = request.modelInput
        val contentDesc = request.contentDesc
        activeUserMsg = userMsg
        val history = turns.flatMap { it.modelMessages() }
        val timeline = WorkTimelineBuilder()
        workEvents = emptyList()
        response = ""
        turnUsage = null
        var turnTranscript: List<ChatMessage> = emptyList()
        providerNotice = null
        val diagnosticId = Diagnostics.newOperationId()
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
        scope.launchDiagnostic("SHARE", diagnosticId) launch@{
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
                        images = images.ifEmpty { null },
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
            } finally {
                isStreaming = false
                recordTurn()
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
        }
    }

    /** Ход в БД: в сохранённый диалог, при autoSave — создать новый, иначе в память. */
    private fun persistTurn(stored: StoredTurn, nameSeed: String) {
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

    private fun generateTitle(nameSeed: String, expectedFirstTurn: StoredTurn) {
        if (titleLocked || turns.firstOrNull() !== expectedFirstTurn) return
        scope.launch(
            DiagnosticContext(
                expectedFirstTurn.diagnosticOperationId ?: Diagnostics.newOperationId(),
            ),
        ) {
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
}
