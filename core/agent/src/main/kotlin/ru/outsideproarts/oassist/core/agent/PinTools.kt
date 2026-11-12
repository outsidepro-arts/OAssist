package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.model.PinChange
import ru.outsideproarts.oassist.core.model.parseMessageId

/**
 * Инструменты закрепления сообщений (`message_pin`/`message_unpin`). Закреплённое
 * сообщение не подлежит усечению истории (медиа всегда в контексте). Инструменты
 * не трогают хранилище диалогов: эффект возвращается в [ToolResult.pinChange],
 * применяется к живой истории циклом агента и сохраняется вызывающим слоем
 * вместе с ходом. Подтверждение — группа `APP_DATA.WRITE` (как у memory_put).
 */
class MessagePinTool : Tool {
    override val schema = ToolSchema(
        name = "message_pin",
        description = "Закрепить сообщение диалога: его изображения и голосовое сообщение всегда остаются " +
            "в контексте и не удаляются при усечении истории. message_id — значение поля id конверта " +
            "сообщения (history, tool_result, current_user_input). note — зачем закрепляется сообщение " +
            "(например: «референсное фото для сверки человека»).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("message_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Id сообщения из поля id его конверта")
                })
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "Зачем закрепляется сообщение")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("message_id")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val id = runCatching { arguments["message_id"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val note = runCatching { arguments["note"]?.jsonPrimitive?.content }.getOrNull()?.trim()
            ?.take(PIN_NOTE_LIMIT).orEmpty()
        return if (note.isEmpty()) "Закрепить сообщение $id" else "Закрепить сообщение $id: $note"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val rawId = arguments["message_id"] as? JsonPrimitive
        if (rawId == null || !rawId.isString) return ToolResult(false, "message_id должен быть строкой")
        val messageId = rawId.content.trim()
        if (messageId.isEmpty() || parseMessageId(messageId) == null) {
            return ToolResult(
                false,
                "Некорректный message_id «$messageId»: ожидается адрес сообщения вида " +
                    "<id хода>:<индекс> из поля id конверта сообщения.",
            )
        }
        val note = (arguments["note"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(PIN_NOTE_LIMIT)
        return ToolResult(
            true,
            "Сообщение закреплено: его медиа останутся в контексте до открепления.",
            pinChange = PinChange(messageId, pinned = true, note = note),
        )
    }
}

class MessageUnpinTool : Tool {
    override val schema = ToolSchema(
        name = "message_unpin",
        description = "Открепить ранее закреплённое сообщение диалога: его медиа снова подлежат усечению " +
            "истории на общих основаниях. message_id — значение поля id конверта сообщения.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("message_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Id сообщения из поля id его конверта")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("message_id")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val id = runCatching { arguments["message_id"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return "Открепить сообщение $id"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val rawId = arguments["message_id"] as? JsonPrimitive
        if (rawId == null || !rawId.isString) return ToolResult(false, "message_id должен быть строкой")
        val messageId = rawId.content.trim()
        if (messageId.isEmpty() || parseMessageId(messageId) == null) {
            return ToolResult(
                false,
                "Некорректный message_id «$messageId»: ожидается адрес сообщения вида " +
                    "<id хода>:<индекс> из поля id конверта сообщения.",
            )
        }
        return ToolResult(
            true,
            "Сообщение откреплено.",
            pinChange = PinChange(messageId, pinned = false),
        )
    }
}

/** Кап заметки закрепления: она попадает в каждый будущий запрос модели. */
private const val PIN_NOTE_LIMIT = 200
