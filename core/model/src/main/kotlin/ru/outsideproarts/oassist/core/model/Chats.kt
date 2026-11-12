package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Принадлежность чата: обычный или закреплённый «последняя сессия ассистента». */
@Serializable
enum class ChatKind {
    REGULAR,
    /** Последняя сессия ассистента: одна запись, перезаписывается каждой новой сессией. */
    ASSISTANT,
}

/** Строка списка чатов (шторка/шаринг): метаданные без сообщений. */
data class ChatSummary(
    val id: String,
    val title: String,
    val kind: ChatKind,
    /** Режим, в котором чат создан (CHAT/SHARE/ASSISTANT) — метка происхождения. */
    val mode: AssistMode,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Запись полотна «Ход работы» одного хода: блоки мыслей и вызовы тулов
 *  В ХРОНОЛОГИИ появления (DeepSeek-стиль: мысли → тул → мысли → …). */
@Serializable
sealed interface WorkEvent {
    /** Блок «мышлений» модели (reasoning) и/или комментария агента. */
    @Serializable
    @SerialName("thought")
    data class Thought(val text: String) : WorkEvent

    /** Вызов инструмента; [output] null — вызов ещё без результата (стрим идёт). */
    @Serializable
    @SerialName("tool")
    data class Tool(
        val name: String,
        val output: String? = null,
        val isError: Boolean = false,
        val images: List<ImagePart> = emptyList(),
    ) : WorkEvent
}

/**
 * Полностью сохранённый ход диалога: проекция для ленты UI + точный транскрипт
 * хода для модели. [turn] — сообщения ассистента/тулов хода (как отдаёт
 * `AgentLoop.stream(onTurnMessages)`): assistant с `toolCalls` → TOOL с `toolCallId`.
 *
 * [work] — хронологическое полотно (мысли + тулы в порядке появления).
 * Legacy-ходы с `thought`+`tools` мигрируют в него при декодировании (TurnCodec).
 */
data class StoredTurn(
    val userText: String = "",
    val userAudio: AudioPart? = null,
    val userImages: List<ImagePart> = emptyList(),
    /** Закрепление реплики пользователя (медиа не усекаются, см. [MessagePin]). */
    val userPin: MessagePin? = null,
    /** Полотно «Ход работы» в хронологии (мыслевые блоки чередуются с тулами). */
    val work: List<WorkEvent> = emptyList(),
    /** Основной ответ ассистента. */
    val answer: String = "",
    val usage: ProviderUsage? = null,
    /** Транскрипт хода для модели, без system/user. */
    val turn: List<ChatMessage> = emptyList(),
    /** New typed user request/context. Null keeps legacy userText replay behavior. */
    val modelUserInput: ModelUserInput? = null,
    val diagnosticOperationId: String? = null,
) {
    /** История для модели; при пустом транскрипте старого чата восстанавливается сохранённый ответ. */
    fun modelMessages(): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatRole.USER,
            content = modelUserInput?.requestText ?: userText,
            audio = userAudio,
            images = userImages,
            pin = userPin,
            contextData = modelUserInput?.context.orEmpty(),
        ),
    ) + turn.ifEmpty {
        if (answer.isNotBlank()) listOf(ChatMessage(ChatRole.ASSISTANT, answer)) else emptyList()
    }

    /**
     * Закрепить/открепить одно сообщение хода по его индексу в [modelMessages]:
     * 0 — реплика пользователя, `k > 0` — `turn[k - 1]`. Индекс вне хода —
     * no-op (ход возвращается без изменений).
     */
    fun withMessagePin(messageIndex: Int, pinned: Boolean, note: String? = null): StoredTurn {
        val pin = if (pinned) MessagePin(note) else null
        return when (messageIndex) {
            0 -> copy(userPin = pin)
            in 1..turn.size -> copy(turn = turn.mapIndexed { index, message ->
                if (index == messageIndex - 1) message.copy(pin = pin) else message
            })
            else -> this
        }
    }

    /** Применить закрепления текущего хода (`current:<индекс>`) к сохраняемому ходу. */
    fun withPinChanges(changes: List<PinChange>): StoredTurn = changes.fold(this) { acc, change ->
        val ref = parseMessageId(change.messageId) ?: return@fold acc
        if (ref.turnId != CURRENT_TURN_ID) return@fold acc
        acc.withMessagePin(ref.index, change.pinned, change.note)
    }
}

/** Ссылка на сообщение хода: [index] 0 — реплика пользователя, `k > 0` — транскрипт `k - 1`. */
data class MessageRef(val turnId: String, val index: Int)

/** Id текущего (ещё не записанного) хода в адресах сообщений запроса. */
const val CURRENT_TURN_ID = "current"

/** Адрес сообщения хода для инструментов закрепления: `<id хода>:<индекс>`. */
fun turnMessageId(turnId: String, messageIndex: Int): String = "$turnId:$messageIndex"

/** Адрес сообщения текущего хода запроса: `current:<индекс>`. */
fun currentMessageId(messageIndex: Int): String = "$CURRENT_TURN_ID:$messageIndex"

/** Разбор адреса сообщения `<id хода>:<индекс>`; null — неизвестный формат. */
fun parseMessageId(messageId: String): MessageRef? {
    val index = messageId.substringAfterLast(':').toIntOrNull() ?: return null
    if (index < 0) return null
    val turnId = messageId.substringBeforeLast(':')
    if (turnId.isEmpty()) return null
    return MessageRef(turnId, index)
}

/**
 * Сохранённый ход с идентификатором его строки: стабильная ручка для удаления
 * сообщения и отката истории (`deleteTurn`/`deleteTurnsAfter`).
 */
data class StoredTurnEntry(
    val id: String,
    val turn: StoredTurn,
) {
    /**
     * История для модели с адресами сообщений (`<id хода>:<индекс>`) для
     * инструментов закрепления. Адреса вычисляются всегда заново: сохранённые
     * runtime-метаданные не должны переезжать между ходами.
     */
    fun modelMessages(): List<ChatMessage> =
        turn.modelMessages().mapIndexed { index, message -> message.copy(id = turnMessageId(id, index)) }
}
