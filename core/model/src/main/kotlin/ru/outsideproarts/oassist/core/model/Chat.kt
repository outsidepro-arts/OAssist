package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.Serializable

/** Доменное сообщение чата: роль + контент (текстовый канал). */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /** Для [ChatRole.TOOL]: id вызова, на который отвечаем. */
    val toolCallId: String? = null,
    /** Для [ChatRole.ASSISTANT]: вызовы инструментов этого хода. */
    val toolCalls: List<ToolCallRef>? = null,
    /** Для [ChatRole.USER]: голосовое приложение (PTT). Null — обычное текстовое. */
    val audio: AudioPart? = null,
    /** Образы к сообщению (только USER/TOOL): скриншоты, вложения Share. Сжатый JPEG, см. ImageCodec. */
    val images: List<ImagePart> = emptyList(),
    /** Для [ChatRole.ASSISTANT]: мысли модели хода (replay thinking-блоков Anthropic / reasoning-item'ов Responses). */
    val reasoning: ReasoningBlock? = null,
    /** Typed attachment for untrusted user/context data. It never carries a chat role. */
    val contextData: List<ContextData> = emptyList(),
)

/**
 * Мысли модели, привязанные к assistant-сообщению хода.
 *
 * [text] — display-текст (полотно «Ход работы») и thinking-блок Anthropic;
 * [signature] — подпись Anthropic, обязательна при обратной передаче
 * `thinking`-блока (иначе API 400); [replayJson] — протокольный JSON
 * reasoning-item'ов OpenAI Responses для stateless-replay.
 */
@Serializable
data class ReasoningBlock(
    val text: String = "",
    val signature: String? = null,
    val replayJson: String? = null,
) {
    fun isEmpty(): Boolean = text.isEmpty() && signature == null && replayJson == null
}

/**
 * Аудио-приложение к user-ходу: WAV 16 кГц моно в base64 (см. `WavEncoder`).
 * Строкой, а не ByteArray — чтобы не ломать equals истории и не тащить
 * бинарник через слои. Тяжёлое (~3.8 МБ/мин base64): кап длины — в захвате.
 */
@Serializable
data class AudioPart(
    val wavBase64: String,
    val mimeType: String = "audio/wav",
    val durationSec: Int = 0,
)

/**
 * Изображение в user-/tool-сообщении: сжатый JPEG в base64 (см. ImageCodec).
 * Строкой, не ByteArray — цел equals истории и тестов (как у [AudioPart]).
 */
@Serializable
data class ImagePart(
    val imageBase64: String,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
)

/** Ссылка на вызов инструмента в истории (пара к tool-результатам). */
@Serializable
data class ToolCallRef(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** Результат выполнения инструмента (`role: "tool"` + `tool_call_id`). */
    TOOL,
}
