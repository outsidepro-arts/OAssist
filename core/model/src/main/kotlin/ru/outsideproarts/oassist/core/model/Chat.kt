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
    /**
     * Адресация сообщения для инструментов закрепления (`<id хода>:<индекс>`,
     * см. `StoredTurnEntry.modelMessages`). Runtime-метаданные: в транскрипт и
     * payload хода не сохраняются, при загрузке вычисляются заново.
     */
    val id: String? = null,
    /** Закрепление сообщения: медиа не вырезаются бюджетами истории (см. [MessagePin]). */
    val pin: MessagePin? = null,
)

/**
 * Закрепление сообщения: его изображения и аудио не вырезаются при усечении
 * истории (бюджеты образов/голоса, `sanitizeHistory`) и обязаны сохраняться
 * будущим механизмом компактизации. [note] — зачем закреплено (для модели).
 */
@Serializable
data class MessagePin(val note: String? = null)

/**
 * Эффект инструментов `message_pin`/`message_unpin`: закрепить ([pinned]) или
 * открепить сообщение с адресом [messageId] (`<id хода>:<индекс>` или
 * `current:<индекс>` текущего хода). Применяется к истории циклом агента
 * и сохраняется вызывающим слоем вместе с ходом.
 */
data class PinChange(
    val messageId: String,
    val pinned: Boolean,
    val note: String? = null,
)

/**
 * Мысли модели, привязанные к assistant-сообщению хода.
 *
 * [text] — display-текст (полотно «Ход работы»);
 * [signature] — legacy-подпись единственного thinking-блока Anthropic.
 * Новые ответы используют [anthropicReplay], сохраняя границы блоков.
 * [replayJson] — протокольный JSON
 * reasoning-item'ов OpenAI Responses для stateless-replay.
 */
@Serializable
data class ReasoningBlock(
    val text: String = "",
    val signature: String? = null,
    val replayJson: String? = null,
    /** Complete Anthropic response blocks; separate from display text and Responses replay. */
    val anthropicReplay: AnthropicReplay? = null,
    /** Ordered Gemini stream content; independent of the display text. */
    val geminiReplay: GeminiReplay? = null,
) {
    fun isEmpty(): Boolean = text.isEmpty() && signature == null && replayJson == null && anthropicReplay == null && geminiReplay == null
}

/** Opaque, ordered assistant content. Only the originating Anthropic endpoint may replay it. */
@Serializable
data class AnthropicReplay(
    val origin: String,
    val blocksJson: String,
)

/** Signed parts must retain their position and may only return to their originating model. */
@Serializable
data class GeminiReplay(
    val origin: String,
    val contentsJson: String,
)

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
    /** Непрозрачная подпись Gemini: возвращается с тем же вызовом без изменений. */
    val thoughtSignature: String? = null,
    /** Gemini wire identity; independent of the locally remapped [id]. Null for legacy/id-less calls. */
    val geminiFunctionCallId: String? = null,
)

@Serializable
enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** Результат выполнения инструмента (`role: "tool"` + `tool_call_id`). */
    TOOL,
}
