package ru.outsideproarts.oassist.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.StoredTurnEntry
import ru.outsideproarts.oassist.core.ui.components.ChatScrollRequester

/** Отложенный ход: сеть пропала до/в начале стрима, повторим фронтом online. */
internal data class PendingChatTurn(val text: String, val audio: AudioPart?, val turnId: String)

/**
 * Сессионное состояние чата, переживающее уход с вкладки (и поворот экрана):
 * activity-scoped ViewModel живёт, пока активно приложение — лента, ходы,
 * черновик и идущий стрим не сбрасываются при переключении на «Настройки».
 *
 * UI-локальные состояния (окно инфо, диалоги, шторка, PTT) остаются
 * в композиции ChatScreen — им смена вкладок не мешает.
 */
class ChatSessionViewModel : ViewModel() {

    val messages = mutableStateListOf<ChatItem>()
    // Ходы накапливаются в памяти — источник истины для истории модели и записи в БД.
    // Каждый ход несёт id своей строки (ручка удаления/отката).
    val turnStore = mutableStateListOf<StoredTurnEntry>()
    var diagnosticOperations = mutableListOf<String>()

    /**
     * Задания прокрутки ленты: ставятся при открытии/сбросе диалога и отправке
     * сообщения, исполняются UI (см. rememberChatListScroll в ChatScreen).
     */
    val scrollRequester = ChatScrollRequester()

    var chatId by mutableStateOf<String?>(null)
    var chatKind by mutableStateOf(ChatKind.REGULAR)
    var chatTitle by mutableStateOf("")
    var titleLocked by mutableStateOf(false)
    var autoSaveChats by mutableStateOf(true)
    var input by mutableStateOf(TextFieldValue(""))
    var isStreaming by mutableStateOf(false)
    var providerNotice by mutableStateOf<ru.outsideproarts.oassist.core.provider.ChatChunk.ProviderNotice.Kind?>(null)
    var streamJob by mutableStateOf<Job?>(null)
    /** Ход, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    internal var pendingRetry by mutableStateOf<PendingChatTurn?>(null)
    var pendingApproval by mutableStateOf<Pair<ApprovalRequest, AgentLoop>?>(null)

    /**
     * Скоуп сессии чата: не гибнет при disposal композиции (вкладки/жесты),
     * отменяется только вместе с ViewModel — «пока приложение активно».
     * Свой, не viewModelScope — без зависимости от lifecycle-viewmodel-ktx.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val turnSavers = ChatTurnSavers(scope)
    internal var historyJob: Job? = null
    internal var historyGeneration = 0L
    internal var historyChatId: String? = null
    var isLoadingHistory by mutableStateOf(false)
        internal set

    override fun onCleared() {
        pendingRetry = null
        historyJob?.cancel()
        val finishingStream = streamJob
        finishingStream?.cancel()
        // The cancelling stream still owns its terminal transcript and can enqueue its save.
        scope.launch {
            try {
                finishingStream?.join()
                turnSavers.awaitSaved()
            } finally {
                scope.cancel()
            }
        }
        // Сессия чата заканчивается с закрытием приложения: «разрешено на
        // сессию» и агрегаты usage привязаны к жизни чата, а не к вкладке.
        runCatching {
            val factory = AgentFactory.get()
            factory.clearSessionApprovals(AssistMode.CHAT)
            factory.resetUsageTotals()
        }
    }
}
