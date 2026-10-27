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
    /** Полотно «Ход работы» в хронологии (мыслевые блоки чередуются с тулами). */
    val work: List<WorkEvent> = emptyList(),
    /** Основной ответ ассистента. */
    val answer: String = "",
    val usage: ProviderUsage? = null,
    /** Транскрипт хода для модели, без system/user. */
    val turn: List<ChatMessage> = emptyList(),
    /** New typed user request/context. Null keeps legacy userText replay behavior. */
    val modelUserInput: ModelUserInput? = null,
) {
    /** История для модели; при пустом транскрипте старого чата восстанавливается сохранённый ответ. */
    fun modelMessages(): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatRole.USER,
            content = modelUserInput?.requestText ?: userText,
            audio = userAudio,
            images = userImages,
            contextData = modelUserInput?.context.orEmpty(),
        ),
    ) + turn.ifEmpty {
        if (answer.isNotBlank()) listOf(ChatMessage(ChatRole.ASSISTANT, answer)) else emptyList()
    }
}
