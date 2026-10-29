package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import ru.outsideproarts.oassist.core.model.PrivilegeLevel
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import kotlinx.coroutines.currentCoroutineContext
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder
import ru.outsideproarts.oassist.core.model.diagnostics.emit

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
    /** Образы в результате (скриншоты): уходят в TOOL-сообщение, в модель — только при vision. */
    val images: List<ImagePart> = emptyList(),
    /** App-owned instruction attachment, populated only by a successful built-in skill lookup. */
    val ownerInstructions: String? = null,
)

/** Инструмент агента. */
interface Tool {
    val schema: ToolSchema
    fun diagnosticSecrets(): Collection<String> = emptyList()

    /**
     * Ключ группы разрешений: решение пользователя (разрешить/запретить) действует
     * на всю группу. Дефолт — по имени тула; MCP-тулы переопределяют на `mcp:<id>`.
     */
    val groupKey: String
        get() = ToolGroups.groupOf(schema.name)

    /** Минимальный уровень привилегий для выполнения. */
    val privilege: PrivilegeLevel
        get() = PrivilegeLevel.NONE

    /** Системные runtime-разрешения, без которых выполнение невозможно. */
    val requiredPermissions: List<String>
        get() = emptyList()

    /**
     * Человекочитаемое описание вызова для диалога подтверждения.
     * Дефолт — имя тула с аргументами `ключ=значение`; инструменты с осмысленным
     * действием переопределяют на понятный текст без секретов.
     */
    fun describeForConfirmation(arguments: JsonObject): String {
        if (arguments.isEmpty()) return schema.name
        val rendered = arguments.entries.joinToString(", ") { (key, value) ->
            val text = value.toString().trim('"')
            "$key=${text.take(ARG_VALUE_LIMIT)}"
        }
        return "${schema.name}($rendered)"
    }

    suspend fun execute(arguments: JsonObject): ToolResult
}

/** Реестр инструментов с учётом пользовательских настроек (вкл/выкл). */
class ToolRegistry(
    private val tools: List<Tool>,
    private val disabled: Set<String> = emptySet(),
    private val diagnostics: DiagnosticRecorder,
) {

    /** Схемы для модели: только включённые — выключенные модель не должна вызывать. */
    fun schemas(): List<ToolSchema> =
        tools.filter { it.schema.name !in disabled }.map { it.schema }

    fun find(name: String): Tool? = tools.find { it.schema.name == name }

    fun isEnabled(name: String): Boolean = name !in disabled

    fun diagnosticSecrets(): List<String> = tools.flatMap { it.diagnosticSecrets() }

    /** Группа разрешений тула; неизвестный — [ToolGroups.UNKNOWN] (fail-closed). */
    fun groupKey(name: String): String = find(name)?.groupKey ?: ToolGroups.UNKNOWN

    suspend fun execute(name: String, arguments: JsonObject): ToolResult {
        val operation = Diagnostics.newOperationId()
        val parent = currentCoroutineContext()[DiagnosticContext]?.operationId
        val secrets = diagnosticSecrets()
        diagnostics.emit(DiagnosticKind.OPERATION_START, operation, parentOperationId = parent,
            attributes = mapOf("operation" to "tool", "name" to name), secrets = secrets)
        val tool = find(name) ?: run {
            diagnostics.emit(DiagnosticKind.OPERATION_END, operation, attributes = mapOf("reason" to "unknown"), secrets = secrets)
            return ToolResult(false, "Инструмент '$name' не найден")
        }
        if (!isEnabled(name)) {
            diagnostics.emit(DiagnosticKind.OPERATION_END, operation, attributes = mapOf("reason" to "disabled"), secrets = secrets)
            return ToolResult(false, "Инструмент '$name' отключён в настройках")
        }
        // runCatching глотает и CancellationException: «Стоп» превращался бы
        // в фейковый ToolResult(false) в ленте/таймлайне — пробрасываем отмену.
        return try {
            val result = tool.execute(arguments)
            diagnostics.emit(DiagnosticKind.OPERATION_END, operation,
                attributes = mapOf("success" to result.success.toString()), secrets = secrets)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            diagnostics.emit(DiagnosticKind.OPERATION_CANCELLED, operation, secrets = secrets)
            throw e
        } catch (e: Throwable) {
            diagnostics.emit(DiagnosticKind.OPERATION_ERROR, operation,
                attributes = mapOf("error" to e.javaClass.simpleName), secrets = secrets)
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}

private const val ARG_VALUE_LIMIT = 80
