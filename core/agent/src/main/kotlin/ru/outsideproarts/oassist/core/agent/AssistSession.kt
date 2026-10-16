package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart

/**
 * Сессия оверлея ассистента: контекст живёт, пока окно открыто, и уничтожается при закрытии.
 * История — полные пары tool_calls/tool_result (экспортируются [AgentLoop.stream] через
 * `onTurnMessages`), чтобы следующий ход видел вызовы инструментов.
 */
class AssistSession internal constructor() {

    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()

    /** Снимок истории для [AgentLoop.stream]. */
    val history: List<ChatMessage>
        get() = synchronized(lock) { messages.toList() }

    /** Контекст текущего экрана; обновляется каждым assist-жестом. */
    @Volatile
    var screenContext: String? = null

    /** Снимок экрана текущего жеста (если включён attachScreenshot); расходуется первым вопросом. */
    @Volatile
    var screenImage: ImagePart? = null

    /** Переключатель голосового ввода (play/pause): ON — авто-диалог разрешён. */
    @Volatile
    var voiceLoop: Boolean = true

    /** Добавить реплику пользователя и сообщения её хода (assistant/tool, без system/user). */
    fun appendTurn(user: ChatMessage, turn: List<ChatMessage>) {
        synchronized(lock) {
            messages.add(user)
            messages.addAll(turn)
            trim()
        }
    }

    private fun trim() {
        while (messages.size > MAX_MESSAGES || totalChars() > MAX_CHARS) {
            if (messages.isEmpty()) return
            var cut = 1
            while (cut < messages.size && messages[cut].role != ChatRole.USER) cut++
            messages.subList(0, cut).clear()
        }
        // Бюджет образов: снимки в памяти сессии тоже не должны копиться без меры.
        val budgeted = enforceImageBudget(messages.toList(), MAX_IMAGE_MESSAGES)
        if (budgeted.size == messages.size) {
            for (i in messages.indices) messages[i] = budgeted[i]
        }
    }

    private fun totalChars(): Int = messages.sumOf { it.content.length }

    companion object {
        const val MAX_MESSAGES = 40
        const val MAX_CHARS = 16_000
    }
}

/**
 * Процесс-хранилище текущей сессии: один оверлей — одна сессия.
 * [clear] — жёсткое уничтожение контекста при закрытии окна (без grace-периода).
 */
object AssistSessionStore {

    @Volatile
    private var session: AssistSession? = null

    fun getOrCreate(): AssistSession {
        synchronized(this) {
            return session ?: AssistSession().also { session = it }
        }
    }

    fun current(): AssistSession? = session

    fun clear() {
        synchronized(this) { session = null }
    }
}
