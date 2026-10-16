package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.Serializable

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

/** Результат вызова инструмента в полотне «Ход работы» сохранённого хода. */
data class StoredTool(
    val name: String,
    val output: String? = null,
    val isError: Boolean = false,
    val images: List<ImagePart> = emptyList(),
)

/**
 * Полностью сохранённый ход диалога: проекция для ленты UI + точный транскрипт
 * хода для модели. [turn] — сообщения ассистента/тулов хода (как отдаёт
 * `AgentLoop.stream(onTurnMessages)`): assistant с `toolCalls` → TOOL с `toolCallId`.
 */
data class StoredTurn(
    val userText: String = "",
    val userAudio: AudioPart? = null,
    val userImages: List<ImagePart> = emptyList(),
    /** Размышления/комментарии к вызовам (в свёрнутое полотно). */
    val thought: String = "",
    val tools: List<StoredTool> = emptyList(),
    /** Основной ответ ассистента. */
    val answer: String = "",
    val usage: ProviderUsage? = null,
    /** Транскрипт хода для модели, без system/user. */
    val turn: List<ChatMessage> = emptyList(),
) {
    /** История для модели: реплика пользователя + транскрипт хода. */
    fun modelMessages(): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatRole.USER,
            content = userText,
            audio = userAudio,
            images = userImages,
        ),
    ) + turn
}
