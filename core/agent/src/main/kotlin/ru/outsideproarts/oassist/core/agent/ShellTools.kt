package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.device.PrivExecutor
import ru.outsideproarts.oassist.core.device.ShellPolicy
import ru.outsideproarts.oassist.core.model.PrivilegeLevel

/**
 * Привилегированные тулы (M5.1): выполнение только при SHIZUKU/ROOT,
 * иначе явная ошибка «нет привилегий». `shell_exec` — строгий read-only
 * белый список ([ShellPolicy]); запись настроек — отдельным тулом.
 */
private fun requirePrivileged(exec: PrivExecutor): ToolResult? =
    if (exec.level < PrivilegeLevel.SHIZUKU) {
        ToolResult(false, ShellPolicy.NO_PRIVILEGES_MESSAGE)
    } else {
        null
    }

private fun stringArg(arguments: JsonObject, name: String): String? =
    arguments[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

class ShellExecTool(private val exec: PrivExecutor) : Tool {
    override val schema = ToolSchema(
        name = "shell_exec",
        description = "Выполнить read-only системную команду (settings get/list, getprop, dumpsys). " +
            "Требует Shizuku или root; без привилегий вернёт явную ошибку.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Команда одной строкой, например 'settings get secure adb_enabled'")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("command"))
            })
        },
    )
    override val privilege: PrivilegeLevel = PrivilegeLevel.SHIZUKU

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Выполнить системную команду: ${arguments["command"]?.jsonPrimitive?.content ?: "?"}"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        requirePrivileged(exec)?.let { return it }
        val raw = stringArg(arguments, "command")
            ?: return ToolResult(false, "Не указан command")
        val cmd = raw.trim().split(Regex("\\s+")).take(32)
        ShellPolicy.checkShellExec(cmd)?.let { return ToolResult(false, "Запрещено: $it") }
        return exec.exec(cmd).fold(
            onSuccess = { r ->
                val tail = if (r.truncated) "\n…(вывод обрезан)" else ""
                if (r.exitCode == 0) ToolResult(true, r.stdout + tail)
                else ToolResult(false, "Код ${r.exitCode}: ${r.stdout}$tail")
            },
            onFailure = { ToolResult(false, "Ошибка: ${it.message}") },
        )
    }
}

class ShellGetSettingTool(private val exec: PrivExecutor) : Tool {
    override val schema = ToolSchema(
        name = "shell_get_setting",
        description = "Прочитать системную настройку (system/secure/global). Требует Shizuku или root.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("namespace", buildJsonObject {
                    put("type", "string")
                    put("description", "system, secure или global")
                })
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Ключ настройки")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("namespace"))
                add(kotlinx.serialization.json.JsonPrimitive("key"))
            })
        },
    )
    override val privilege: PrivilegeLevel = PrivilegeLevel.SHIZUKU

    override suspend fun execute(arguments: JsonObject): ToolResult {
        requirePrivileged(exec)?.let { return it }
        val namespace = stringArg(arguments, "namespace")
            ?: return ToolResult(false, "Не указан namespace")
        ShellPolicy.checkNamespace(namespace)?.let { return ToolResult(false, it) }
        val key = stringArg(arguments, "key")
            ?: return ToolResult(false, "Не указан key")
        return exec.getSetting(namespace, key).fold(
            onSuccess = { v -> ToolResult(true, v ?: "(не задано)") },
            onFailure = { ToolResult(false, "Ошибка: ${it.message}") },
        )
    }
}

class ShellPutSettingTool(private val exec: PrivExecutor) : Tool {
    override val schema = ToolSchema(
        name = "shell_put_setting",
        description = "Записать системную настройку (system/secure/global). Требует Shizuku или root.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("namespace", buildJsonObject {
                    put("type", "string")
                    put("description", "system, secure или global")
                })
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Ключ настройки")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Значение")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("namespace"))
                add(kotlinx.serialization.json.JsonPrimitive("key"))
                add(kotlinx.serialization.json.JsonPrimitive("value"))
            })
        },
    )
    override val privilege: PrivilegeLevel = PrivilegeLevel.SHIZUKU

    override fun describeForConfirmation(arguments: JsonObject): String {
        val ns = arguments["namespace"]?.jsonPrimitive?.content ?: "?"
        val key = arguments["key"]?.jsonPrimitive?.content ?: "?"
        val value = arguments["value"]?.jsonPrimitive?.content ?: "?"
        return "Записать настройку $ns/$key = $value"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        requirePrivileged(exec)?.let { return it }
        val namespace = stringArg(arguments, "namespace")
            ?: return ToolResult(false, "Не указан namespace")
        ShellPolicy.checkNamespace(namespace)?.let { return ToolResult(false, it) }
        val key = stringArg(arguments, "key")
            ?: return ToolResult(false, "Не указан key")
        val value = arguments["value"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан value")
        return exec.putSetting(namespace, key, value).fold(
            onSuccess = { ToolResult(true, "Записано: $namespace/$key") },
            onFailure = { ToolResult(false, "Ошибка: ${it.message}") },
        )
    }
}
