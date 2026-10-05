package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.data.skill.SkillStore

/**
 * Навыки агента: чтение — свободно (включённые уже в системном промпте,
 * `skill_get` подтягивает полное тело по имени). Вкл/выкл и правки —
 * только из настроек, не тулами.
 */
class SkillListTool(private val skills: SkillStore) : Tool {
    override val schema = ToolSchema(
        name = "skill_list",
        description = "Список включённых навыков (имя — краткое описание). " +
            "Полное тело навыка — через skill_get.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val enabled = skills.list().filter { it.enabled }
        if (enabled.isEmpty()) return ToolResult(true, "(нет включённых навыков)")
        return ToolResult(true, enabled.joinToString("\n") { "${it.name} — ${it.title}" })
    }
}

class SkillGetTool(private val skills: SkillStore) : Tool {
    override val schema = ToolSchema(
        name = "skill_get",
        description = "Прочитать полное тело навыка по имени (инструкция для выполнения).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Имя навыка из skill_list")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("name")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val name = runCatching { arguments["name"]?.jsonPrimitive?.content }
            .getOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return ToolResult(false, "Не указано имя навыка")
        val skill = skills.get(name)
            ?: return ToolResult(false, "Навык «$name» не найден")
        if (!skill.enabled) return ToolResult(false, "Навык «$name» выключен в настройках")
        return ToolResult(true, skill.body)
    }
}
