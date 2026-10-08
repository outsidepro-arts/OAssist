package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Громкость, «Не беспокоить», яркость (M5.2). Спецдоступы — явные подсказки, не молча. */
class VolumeSetTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "volume_set",
        description = "Задать громкость потока процентами: music/ring/alarm/notification.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "music, ring, alarm или notification")
                })
                put("percent", buildJsonObject {
                    put("type", "integer")
                    put("description", "Громкость 0–100")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("stream"))
                add(kotlinx.serialization.json.JsonPrimitive("percent"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val stream = arguments["stream"]?.jsonPrimitive?.content ?: "?"
        val percent = arguments["percent"]?.jsonPrimitive?.content ?: "?"
        return "Установить громкость $stream: $percent%"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val stream = arguments["stream"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан stream")
        val percent = arguments["percent"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан percent (0–100)")
        return actions.setVolume(stream, percent)
    }
}

class DndSetTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "dnd_set",
        description = "Включить/выключить «Не беспокоить». Требует спецдоступ (подсказка — в ответе).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true — включить, false — выключить")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("enabled"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val enabled = arguments["enabled"]?.jsonPrimitive?.content == "true"
        return if (enabled) "Включить «Не беспокоить»" else "Выключить «Не беспокоить»"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = arguments["enabled"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан enabled")
        val enabled = when (raw) {
            "true" -> true
            "false" -> false
            else -> return ToolResult(false, "enabled: true/false")
        }
        return actions.setDnd(enabled)
    }
}

class BrightnessSetTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "brightness_set",
        description = "Задать яркость экрана процентами (переводит в ручной режим). Требует спецдоступ.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("percent", buildJsonObject {
                    put("type", "integer")
                    put("description", "Яркость 0–100")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("percent"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val percent = arguments["percent"]?.jsonPrimitive?.content ?: "?"
        return "Установить яркость экрана: $percent%"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val percent = arguments["percent"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан percent (0–100)")
        return actions.setBrightness(percent)
    }
}
