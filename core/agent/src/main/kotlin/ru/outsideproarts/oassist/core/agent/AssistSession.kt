package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.MessagePin
import ru.outsideproarts.oassist.core.model.turnMessageId

/**
 * Сессия оверлея ассистента: контекст живёт, пока окно открыто, и уничтожается при закрытии.
 * История — полные пары tool_calls/tool_result (экспортируются [AgentLoop.stream] через
 * `onTurnMessages`), чтобы следующий ход видел вызовы инструментов.
 *
 * История хранится целиком, без обрезки: усечение контекста — владение механизма
 * компактизации, а не сессии. Не добавляйте сюда лимиты — раньше trim() с лимитом
 * символов стирал всю историю включая текущий ход, когда вывод инструмента
 * (web_fetch, ui_get_screen) превышал бюджет.
 */
class AssistSession internal constructor() {

    private val lock = Any()

    /** Ходы сессии: реплика пользователя + её транскрипт; [SessionTurn.id] — id хода в БД. */
    private val units = mutableListOf<SessionTurn>()

    private data class SessionTurn(val id: String, val messages: List<ChatMessage>)

    /** Снимок истории для [AgentLoop.stream]. */
    val history: List<ChatMessage>
        get() = synchronized(lock) { units.flatMap { it.messages } }

    /** Контекст текущего экрана; обновляется каждым assist-жестом. */
    @Volatile
    var screenContext: String? = null

    /** Снимок экрана текущего жеста (если включён attachScreenshot); расходуется первым вопросом. */
    @Volatile
    var screenImage: ImagePart? = null

    /** Переключатель голосового ввода (play/pause): ON — авто-диалог разрешён. */
    @Volatile
    var voiceLoop: Boolean = true

    /**
     * Добавить реплику пользователя и сообщения её хода (assistant/tool, без
     * system/user). [turnId] — id хода в БД: по нему ход стирается из истории;
     * он же задаёт адреса сообщений (`<turnId>:<индекс>`) для закрепления.
     */
    fun appendTurn(user: ChatMessage, turn: List<ChatMessage>, turnId: String = "") {
        synchronized(lock) {
            units.add(
                SessionTurn(
                    turnId,
                    (listOf(user) + turn).mapIndexed { index, message ->
                        message.copy(id = turnMessageId(turnId, index))
                    },
                ),
            )
        }
    }

    /**
     * Закрепить/открепить сообщение в живой истории ([messageId] — адрес
     * `<id хода>:<индекс>`): медиа закреплённых не усекаются бюджетами.
     * Неизвестный адрес — no-op.
     */
    fun pinMessage(messageId: String, pin: MessagePin?) {
        synchronized(lock) {
            for (index in units.indices) {
                val unit = units[index]
                if (unit.messages.none { it.id == messageId }) continue
                units[index] = unit.copy(
                    messages = unit.messages.map { if (it.id == messageId) it.copy(pin = pin) else it },
                )
                return
            }
        }
    }

    /**
     * Стереть ходы по их id (удаление сообщения/откат из истории чата): живое
     * зеркало не должно отдавать модели стёртые сообщения. Реплики незаписанных
     * ходов без id не сопоставлены стираниям и остаются.
     */
    fun removeTurns(turnIds: Set<String>) {
        synchronized(lock) { units.removeAll { it.id in turnIds } }
    }
}

/**
 * Процесс-хранилище текущей сессии: один оверлей — одна сессия.
 * [clear] — жёсткое уничтожение контекста при закрытии окна (без grace-периода).
 */
object AssistSessionStore {

    @Volatile
    private var session: AssistSession? = null
    private var activeOverlayMode: Boolean? = null
    private var epoch = 0L
    private var captureOwner: ScreenCaptureOwner? = null
    private var pendingCapture: PendingScreenCapture? = null

    class ScreenCaptureOwner internal constructor(internal val epoch: Long)

    private data class PendingScreenCapture(
        val token: String,
        val epoch: Long,
        val context: String?,
        val image: ImagePart?,
    )

    fun getOrCreate(): AssistSession {
        synchronized(this) {
            return session ?: AssistSession().also {
                invalidateCapture()
                session = it
            }
        }
    }

    fun current(): AssistSession? = session

    fun activeShowOverlay(): Boolean? = synchronized(this) { activeOverlayMode }

    /** Only the current session may publish its active interface mode. */
    fun activateUi(expected: AssistSession, showOverlay: Boolean) = synchronized(this) {
        if (session === expected) activeOverlayMode = showOverlay
    }

    fun clear() {
        synchronized(this) {
            session = null
            activeOverlayMode = null
            invalidateCapture()
        }
    }

    /** An old service must not clear a replacement owner's context. */
    fun clear(expected: AssistSession) = synchronized(this) {
        if (session === expected) clear()
    }

    /** A new gesture supersedes any capture still waiting for callbacks or permissions. */
    fun beginScreenCapture(): ScreenCaptureOwner = synchronized(this) {
        pendingCapture = null
        ScreenCaptureOwner(epoch).also { captureOwner = it }
    }

    fun cancelScreenCapture(owner: ScreenCaptureOwner) = synchronized(this) {
        if (captureOwner === owner) captureOwner = null
    }

    fun publishScreenCapture(owner: ScreenCaptureOwner, context: String?, image: ImagePart?): String? =
        synchronized(this) {
            if (captureOwner !== owner || owner.epoch != epoch) return@synchronized null
            captureOwner = null
            val token = java.util.UUID.randomUUID().toString()
            pendingCapture = PendingScreenCapture(token, epoch, context, image)
            token
        }

    fun isScreenCapturePending(token: String): Boolean = synchronized(this) {
        pendingCapture?.let { it.token == token && it.epoch == epoch } == true
    }

    fun discardScreenCapture(token: String) = synchronized(this) {
        if (pendingCapture?.token == token) pendingCapture = null
    }

    /** Validate owner and consume the complete snapshot atomically, including absent fields. */
    fun consumeScreenCapture(token: String, allowScreenshot: Boolean): AssistSession? = synchronized(this) {
        val capture = pendingCapture ?: return@synchronized null
        if (capture.token != token || capture.epoch != epoch) return@synchronized null
        pendingCapture = null
        val target = getOrCreate()
        target.screenContext = capture.context
        target.screenImage = capture.image.takeIf { allowScreenshot }
        target
    }

    private fun invalidateCapture() {
        epoch++
        captureOwner = null
        pendingCapture = null
    }
}
