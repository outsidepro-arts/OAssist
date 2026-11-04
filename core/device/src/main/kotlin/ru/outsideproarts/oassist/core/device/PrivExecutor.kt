package ru.outsideproarts.oassist.core.device

import ru.outsideproarts.oassist.core.model.PrivilegeLevel

/**
 * Результат выполнения команды. Вывод обрезан до [maxBytes] с флагом [truncated].
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val truncated: Boolean = false,
)

/**
 * Минимальный исполнитель привилегированных операций.
 * Слои: `intent` → `ui` (a11y) → `privileged` (этот интерфейс).
 * Без привилегий — явная ошибка с подсказкой, никогда не молча.
 */
interface PrivExecutor {
    val level: PrivilegeLevel

    /** Выполнить команду без shell-интерпретации (argv, без `;|&&$()`). */
    suspend fun exec(
        cmd: List<String>,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        maxBytes: Int = ShellPolicy.DEFAULT_MAX_BYTES,
    ): Result<ExecResult>

    suspend fun getSetting(namespace: String, key: String): Result<String?>
    suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit>
    suspend fun inputTap(x: Int, y: Int): Result<Unit>
    suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit>
    suspend fun inputKeyevent(keyCode: Int): Result<Unit>
}

/**
 * Статус привилегий для экрана «Привилегии».
 */
data class PrivilegeStatus(
    val shizukuAvailable: Boolean,
    val shizukuGranted: Boolean,
    val rootAvailable: Boolean,
)

/**
 * Выбор исполнителя: Shizuku > Root > Noop. Noop — честный «нет привилегий».
 */
object PrivilegeRouter {
    fun select(status: PrivilegeStatus, executors: PrivExecutors): PrivExecutor = when {
        status.shizukuAvailable && status.shizukuGranted -> executors.shizuku
        status.rootAvailable -> executors.root
        else -> executors.noop
    }

    fun effectiveLevel(status: PrivilegeStatus): PrivilegeLevel = when {
        status.shizukuAvailable && status.shizukuGranted -> PrivilegeLevel.SHIZUKU
        status.rootAvailable -> PrivilegeLevel.ROOT
        else -> PrivilegeLevel.NONE
    }
}

data class PrivExecutors(
    val noop: PrivExecutor,
    val shizuku: PrivExecutor,
    val root: PrivExecutor,
)

/**
 * Белый список read-only команд для `shell_exec` (M5.1).
 * `input` запрещён напрямую (жесты — только через `ui_*` с фолбэком),
 * `settings put/delete` — только через выделенный метод с ASK.
 */
object ShellPolicy {
    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val MAX_TIMEOUT_MS = 30_000L
    const val DEFAULT_MAX_BYTES = 32 * 1024
    const val MAX_BYTES = 128 * 1024

    const val NO_PRIVILEGES_MESSAGE =
        "Нет привилегий: установите Shizuku или выдайте root-доступ, затем повторите"

    private val READ_ONLY = setOf("settings", "getprop", "dumpsys", "pm", "wm")

    private val SHELL_META = listOf(";", "|", "&&", "$(", "`", "\n")

    /** Проверка команды для `shell_exec`. Возвращает null если разрешена, иначе причину отказа. */
    fun checkShellExec(cmd: List<String>): String? {
        if (cmd.isEmpty()) return "Пустая команда"
        val bin = cmd.first().removePrefix("/system/bin/")
        if (bin == "su" || bin == "sudo") return "Прямой вызов su запрещён"
        if (bin == "input") return "Команда input запрещена: жесты — только через инструменты ui_*"
        if (bin !in READ_ONLY) return "Команда '$bin' вне белого списка M5.1"
        for (arg in cmd) {
            if (SHELL_META.any { it in arg }) return "Метасимволы shell запрещены: команда выполняется без интерпретации"
        }
        val args = cmd.drop(1)
        val safe = when (bin) {
            "settings" -> when (args.firstOrNull()) {
                "get" -> args.size == 3 && checkNamespace(args[1]) == null && isPlainArg(args[2])
                "list" -> args.size == 2 && checkNamespace(args[1]) == null
                else -> false
            }
            "getprop" -> args.isEmpty() || (args.size == 1 && isPlainArg(args[0]))
            // Service-specific dump arguments can modify state (for example, battery reset).
            "dumpsys" -> args.isEmpty() || (args.size == 1 && (args[0] == "-l" || isPlainArg(args[0])))
            "pm" -> args.size == 2 && args[0] == "list" && args[1] in
                setOf("packages", "permission-groups", "permissions", "instrumentation", "features", "libraries", "users")
            "wm" -> args.size == 1 && args[0] in setOf("size", "density")
            else -> false
        }
        return if (safe) null else "Недопустимые аргументы команды '$bin'"
    }

    private fun isPlainArg(arg: String): Boolean = arg.isNotBlank() && !arg.startsWith("-")

    /** Call only after [checkShellExec]; never execute an unchecked user-supplied path. */
    fun shellExecArgv(cmd: List<String>): List<String> =
        listOf("/system/bin/" + cmd.first().removePrefix("/system/bin/")) + cmd.drop(1)

    fun checkNamespace(namespace: String): String? = when (namespace) {
        "system", "secure", "global" -> null
        else -> "Пространство '$namespace': только system/secure/global"
    }

    fun coerceTimeout(timeoutMs: Long): Long = timeoutMs.coerceIn(1, MAX_TIMEOUT_MS)
    fun coerceMaxBytes(maxBytes: Int): Int = maxBytes.coerceIn(1, MAX_BYTES)

    fun capOutput(text: String, maxBytes: Int): Pair<String, Boolean> =
        if (text.length <= maxBytes) text to false
        else text.substring(0, maxBytes) to true
}
