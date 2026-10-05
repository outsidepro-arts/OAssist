package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Сообщение чата или дельта. В потоке дельты поля [role]/[content] могут частично
 * отсутствовать, поэтому оба nullable.
 * `content` полиморфен, как у сервера: строка текста или массив content-parts
 * (текст + `input_audio` для голосовых ходов).
 */
@Serializable
data class ChatMessageDto(
    val role: String? = null,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallRequestDto>? = null,
)

/** Вызов инструмента в исходящем assistant-сообщении (пара к будущим tool-ответам). */
@Serializable
data class ToolCallRequestDto(
    val id: String,
    val type: String = "function",
    val function: ToolCallRequestFunctionDto,
)

@Serializable
data class ToolCallRequestFunctionDto(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val tools: List<ToolSchemaDto>? = null,
)

@Serializable
data class ToolSchemaDto(
    val type: String = "function",
    val function: ToolFunctionSchemaDto,
)

@Serializable
data class ToolFunctionSchemaDto(
    val name: String,
    val description: String? = null,
    val parameters: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class ChatResponseDto(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val model: String? = null,
    val choices: List<ChatChoiceDto> = emptyList(),
)

@Serializable
data class ChatChoiceDto(
    val index: Int = 0,
    val message: ChatMessageDto? = null,
    val delta: ChatDeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatDeltaDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDeltaDto>? = null,
)

@Serializable
data class ToolCallDeltaDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunctionDto? = null,
)

@Serializable
data class ToolCallFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)