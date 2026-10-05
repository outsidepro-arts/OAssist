package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.PrivilegeLevel

/** Описание инструмента для передачи в LLM (OpenAI function calling). */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** Результат выполнения инструмента. */
data class ToolResult(
    val success: Boolean,
    val output: String,
)

/** Инструмент агента. */
interface Tool {
    val schema: ToolSchema

    /** Уровень риска: управляет паузой на подтверждение в [AgentLoop]. */
    val risk: ActionRisk
        get() = ActionRisk.AUTO

    /** Минимальный уровень привилегий для выполнения. */
    val privilege: PrivilegeLevel
        get() = PrivilegeLevel.NONE

    /** Системные runtime-разрешения, без которых выполнение невозможно. */
    val requiredPermissions: List<String>
        get() = emptyList()

    /**
     * Человекочитаемое описание вызова для диалога подтверждения.
     * Дефолт — имя + сырые аргументы; инструменты с [ActionRisk.ASK] и выше
     * переопределяют на понятный текст без секретов.
     */
    fun describeForConfirmation(arguments: JsonObject): String =
        "${schema.name}: $arguments"

    suspend fun execute(arguments: JsonObject): ToolResult
}

/** Реестр инструментов с учётом пользовательских настроек (вкл/выкл, «спрашивать»). */
class ToolRegistry(
    private val tools: List<Tool>,
    private val disabled: Set<String> = emptySet(),
    private val askOverrides: Map<String, Boolean> = emptyMap(),
) {

    /** Схемы для модели: только включённые — выключенные модель не должна вызывать. */
    fun schemas(): List<ToolSchema> =
        tools.filter { it.schema.name !in disabled }.map { it.schema }

    fun find(name: String): Tool? = tools.find { it.schema.name == name }

    fun isEnabled(name: String): Boolean = name !in disabled

    /**
     * Эффективный риск с учётом переопределения пользователя:
     * `true` — минимум ASK (понизить дефолт нельзя),
     * `false` — всегда AUTO, нет записи — дефолт инструмента.
     * Неизвестный инструмент — ASK (fail-closed).
     */
    fun effectiveRisk(name: String): ActionRisk {
        val base = find(name)?.risk ?: return ActionRisk.ASK
        return when (askOverrides[name]) {
            true -> if (base == ActionRisk.AUTO) ActionRisk.ASK else base
            false -> ActionRisk.AUTO
            null -> base
        }
    }

    suspend fun execute(name: String, arguments: JsonObject): ToolResult {
        val tool = find(name) ?: return ToolResult(false, "Инструмент '$name' не найден")
        if (!isEnabled(name)) return ToolResult(false, "Инструмент '$name' отключён в настройках")
        return runCatching { tool.execute(arguments) }
            .getOrElse { ToolResult(false, "Ошибка: ${it.message}") }
    }
}
