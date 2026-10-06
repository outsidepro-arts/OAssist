package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.model.ActionRisk

/** Инструменты чтения уведомлений через пассивный NotificationListenerService. */
class NotifyListTool(private val notify: NotifyActions) : Tool {
    override val schema = ToolSchema(
        name = "notify_list",
        description = "Список активных уведомлений: приложение, заголовок, текст, время, ключ.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = notify.list()
}

class NotifyOpenTool(private val notify: NotifyActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "notify_open",
        description = "Открыть уведомление по ключу из notify_list (запускает приложение).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Ключ уведомления в квадратных скобках из списка")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("key")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Открыть уведомление по запросу пользователя"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан key")
        return notify.open(key)
    }
}

class NotifyClearTool(private val notify: NotifyActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "notify_clear",
        description = "Скрыть уведомление по ключу из notify_list.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Ключ уведомления в квадратных скобках из списка")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("key")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Скрыть уведомление по запросу пользователя"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан key")
        return notify.clear(key)
    }
}

class NotifyReplyTool(private val notify: NotifyActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "notify_reply",
        description = "Ответить текстом на уведомление по ключу из notify_list (только если у него есть действие ответа).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Ключ уведомления в квадратных скобках из списка")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст ответа")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("key"))
                add(kotlinx.serialization.json.JsonPrimitive("text"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val text = arguments["text"]?.jsonPrimitive?.content?.take(MAX_PREVIEW_CHARS) ?: "?"
        return "Ответить на уведомление: «$text»"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан key")
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан text")
        return notify.reply(key, text)
    }

    companion object {
        private const val MAX_PREVIEW_CHARS = 120
    }
}
