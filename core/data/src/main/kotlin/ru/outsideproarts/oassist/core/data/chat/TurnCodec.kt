package ru.outsideproarts.oassist.core.data.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.StoredTool
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.ToolCallRef

@Serializable
internal data class TurnPayload(
    val userText: String = "",
    val userAudio: MediaJson? = null,
    val userImages: List<MediaJson> = emptyList(),
    val thought: String = "",
    val tools: List<ToolJson> = emptyList(),
    val answer: String = "",
    val usage: ProviderUsage? = null,
    val turn: List<MessageJson> = emptyList(),
)

@Serializable
internal data class MessageJson(
    val role: ChatRole,
    val content: String = "",
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallRef>? = null,
    val audio: MediaJson? = null,
    val images: List<MediaJson> = emptyList(),
)

@Serializable
internal data class ToolJson(
    val name: String,
    val output: String? = null,
    val isError: Boolean = false,
    val images: List<MediaJson> = emptyList(),
)

@Serializable
internal data class MediaJson(
    val path: String = "",
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int = 0,
)

/**
 * JSON-кодек хода диалога: [StoredTurn] ↔ payload колонки `turns`.
 * Медиа выносятся файлами через [ChatMediaStore] (в JSON — ref'ы), при чтении
 * гидратируются обратно в base64. Отсутствующий файл — молча выпадает:
 * текстовый контекст цел, бинарник потерян навсегда.
 */
object TurnCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(turn: StoredTurn, chatId: String, turnId: String, media: ChatMediaStore): String {
        val payload = TurnPayload(
            userText = turn.userText,
            userAudio = turn.userAudio?.let { media.writeAudio(chatId, turnId, it)?.toJson() },
            userImages = turn.userImages.mapIndexedNotNull { index, image ->
                media.writeImage(chatId, turnId, "u$index", image)?.toJson()
            },
            thought = turn.thought,
            tools = turn.tools.mapIndexed { toolIndex, tool ->
                ToolJson(
                    name = tool.name,
                    output = tool.output,
                    isError = tool.isError,
                    images = tool.images.mapIndexedNotNull { index, image ->
                        media.writeImage(chatId, turnId, "t${toolIndex}_$index", image)?.toJson()
                    },
                )
            },
            answer = turn.answer,
            usage = turn.usage,
            turn = turn.turn.mapIndexed { index, message ->
                MessageJson(
                    role = message.role,
                    content = message.content,
                    toolCallId = message.toolCallId,
                    toolCalls = message.toolCalls,
                    audio = message.audio?.let { media.writeAudio(chatId, "${turnId}_m$index", it)?.toJson() },
                    images = message.images.mapIndexedNotNull { imageIndex, image ->
                        media.writeImage(chatId, turnId, "m${index}_$imageIndex", image)?.toJson()
                    },
                )
            },
        )
        return json.encodeToString(TurnPayload.serializer(), payload)
    }

    /** Null — payload нечитаем (повреждён); загрузка диалога такие ходы пропускает. */
    fun decode(payload: String, media: ChatMediaStore): StoredTurn? {
        val dto = runCatching {
            json.decodeFromString(TurnPayload.serializer(), payload)
        }.getOrNull() ?: return null
        return StoredTurn(
            userText = dto.userText,
            userAudio = dto.userAudio?.toRef()?.let { media.readAudio(it) },
            userImages = dto.userImages.mapNotNull { it.toRef()?.let(media::readImage) },
            thought = dto.thought,
            tools = dto.tools.map { tool ->
                StoredTool(
                    name = tool.name,
                    output = tool.output,
                    isError = tool.isError,
                    images = tool.images.mapNotNull { it.toRef()?.let(media::readImage) },
                )
            },
            answer = dto.answer,
            usage = dto.usage,
            turn = dto.turn.map { message ->
                ChatMessage(
                    role = message.role,
                    content = message.content,
                    toolCallId = message.toolCallId,
                    toolCalls = message.toolCalls,
                    audio = message.audio?.toRef()?.let { media.readAudio(it) },
                    images = message.images.mapNotNull { it.toRef()?.let(media::readImage) },
                )
            },
        )
    }

    private fun MediaJson.toRef(): MediaRef? = MediaRef(path, mimeType, width, height, durationSec)
        .takeIf { it.path.isNotEmpty() }

    private fun MediaRef.toJson(): MediaJson = MediaJson(path, mimeType, width, height, durationSec)
}
