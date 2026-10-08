package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.data.memory.MemoryStore

/**
 * Память агента (`MEMORY.md`): чтение — свободно, запись и стирание —
 * с подтверждением (персистентные факты о пользователе, прайваси).
 * Секреты в память запрещены — см. MemoryStore.
 */
class MemoryGetTool(private val memory: MemoryStore) : Tool {
    override val schema = ToolSchema(
        name = "memory_get",
        description = "Прочитать память о пользователе (факты из прошлых диалогов). " +
            "Пусто — значит, пока ничего не запомнено.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = memory.read()
        return ToolResult(true, text.ifBlank { "(память пуста)" })
    }
}

class MemoryPutTool(private val memory: MemoryStore) : Tool {
    override val schema = ToolSchema(
        name = "memory_put",
        description = "Записать факт о пользователе в память. " +
            "Только факты (имена, предпочтения, договорённости), никогда секреты/пароли/токены. " +
            "mode=append дописывает строку, mode=replace заменяет всю память.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Факт для запоминания")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "append (по умолчанию) или replace")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val text = runCatching { arguments["text"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val mode = runCatching { arguments["mode"]?.jsonPrimitive?.content }.getOrNull()
        return if (mode == "replace") {
            "Заменить всю память на: $text"
        } else {
            "Запомнить: $text"
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = runCatching { arguments["text"]?.jsonPrimitive?.content }
            .getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult(false, "Пустой text — нечего запоминать")
        val mode = runCatching { arguments["mode"]?.jsonPrimitive?.content }.getOrNull()
        if (mode == "replace") {
            memory.write(text)
        } else {
            memory.append(text)
        }
        return ToolResult(true, "Запомнено")
    }
}

class MemoryForgetTool(private val memory: MemoryStore) : Tool {
    override val schema = ToolSchema(
        name = "memory_forget",
        description = "Убрать из памяти строки, содержащие query. " +
            "Без query — отказ (стирание всей памяти — только из настроек).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Подстрока для удаления")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val query = runCatching { arguments["query"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return "Убрать из памяти всё про: $query"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = runCatching { arguments["query"]?.jsonPrimitive?.content }
            .getOrNull()?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult(false, "Пустой query. Стереть всю память можно только вручную в настройках.")
        }
        val kept = memory.read().lines()
            .filter { it.isNotBlank() && !it.contains(query, ignoreCase = true) }
        return if (kept.size == memory.read().lines().filter { it.isNotBlank() }.size) {
            ToolResult(true, "Ничего про «$query» в памяти нет")
        } else {
            memory.write(kept.joinToString("\n"))
            ToolResult(true, "Убрано из памяти")
        }
    }
}
