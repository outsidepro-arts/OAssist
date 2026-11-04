package ru.outsideproarts.oassist.core.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import ru.outsideproarts.oassist.core.model.PrivilegeLevel
import java.io.File

/**
 * Исполнитель без привилегий: каждая операция — явная ошибка с подсказкой.
 * Fail-closed: агент получает диагноз, а не молчаливый пропуск.
 */
class NoopExecutor : PrivExecutor {
    override val level: PrivilegeLevel = PrivilegeLevel.NONE

    private fun denied(): Result<Nothing> =
        Result.failure(IllegalStateException(ShellPolicy.NO_PRIVILEGES_MESSAGE))

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> =
        denied()

    override suspend fun getSetting(namespace: String, key: String): Result<String?> = denied()
    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> = denied()
    override suspend fun inputTap(x: Int, y: Int): Result<Unit> = denied()
    override suspend fun inputKeyevent(keyCode: Int): Result<Unit> = denied()
    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> =
        denied()
}

/**
 * Root-исполнитель: `su -c` с таймаутом и лимитом вывода.
 * Проверка наличия: `which su` + проба `su -c id`.
 */
class RootExecutor internal constructor(
    private val runProcess: (List<String>, Long, Int) -> ExecResult,
) : PrivExecutor {
    constructor() : this(ProcessRunner::run)
    override val level: PrivilegeLevel = PrivilegeLevel.ROOT
    private val reassertDeadline = BinderDeadline()
    private val reassert = AccessibilityReassert { cmd ->
        val flat = cmd.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        ProcessRunner.runToCompletion(listOf("su", "-c", "exec $flat"))
    }

    override suspend fun reassertAccessibilityService(component: String, onCompletion: () -> Unit): Result<Unit> {
        val notDispatched = java.util.concurrent.atomic.AtomicBoolean()
        return runCatching {
            reassertDeadline.await(ShellPolicy.DEFAULT_TIMEOUT_MS + 5_000L, onCompletion, { notDispatched.set(true) }) {
                try {
                    reassert.run(component)
                } catch (e: Exception) {
                    android.util.Log.e("OAssist", "Accessibility reassert failed", e)
                    throw e
                }
            }
        }.fold({ Result.success(it) }, { Result.failure(if (notDispatched.get()) AccessibilityReassertUnavailableException(it) else it) })
    }

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val which = ProcessBuilder("which", "su")
                .start().apply { waitFor() }.inputStream.bufferedReader().readText().trim()
            if (which.isBlank()) return@runCatching false
            val probe = runInterruptible { execRaw(listOf("su", "-c", "id"), ShellPolicy.DEFAULT_TIMEOUT_MS, ShellPolicy.DEFAULT_MAX_BYTES) }
                .getOrNull() ?: return@runCatching false
            probe.exitCode == 0 && "uid=0" in probe.stdout
        }.getOrDefault(false)
    }

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> =
        ShellPolicy.checkShellExec(cmd)?.let { Result.failure(IllegalArgumentException(it)) }
            ?: execTrusted(ShellPolicy.shellExecArgv(cmd), timeoutMs, maxBytes)

    private suspend fun execTrusted(
        cmd: List<String>,
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        maxBytes: Int = ShellPolicy.DEFAULT_MAX_BYTES,
    ): Result<ExecResult> =
        withContext(Dispatchers.IO) {
            val flat = cmd.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
            runInterruptible {
                execRaw(listOf("su", "-c", flat), ShellPolicy.coerceTimeout(timeoutMs), ShellPolicy.coerceMaxBytes(maxBytes))
            }
        }

    private fun execRaw(cmd: List<String>, timeoutMs: Long, maxChars: Int): Result<ExecResult> =
        runCatching { runProcess(cmd, timeoutMs, maxChars) }

    override suspend fun getSetting(namespace: String, key: String): Result<String?> {
        ShellPolicy.checkNamespace(namespace)?.let { return Result.failure(IllegalArgumentException(it)) }
        return execTrusted(listOf("/system/bin/settings", "get", namespace, key)).settingValue()
    }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
        ShellPolicy.checkNamespace(namespace)?.let { return Result.failure(IllegalArgumentException(it)) }
        return execTrusted(listOf("/system/bin/settings", "put", namespace, key, value)).checkExitCode().map { }
    }

    override suspend fun inputTap(x: Int, y: Int): Result<Unit> =
        execTrusted(listOf("/system/bin/input", "tap", x.toString(), y.toString())).checkExitCode().map { }

    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> =
        execTrusted(
            listOf(
                "/system/bin/input", "swipe",
                fromX.toString(), fromY.toString(), toX.toString(), toY.toString(),
                durationMs.coerceIn(1, 5000).toString(),
            ),
        ).checkExitCode().map { }

    override suspend fun inputKeyevent(keyCode: Int): Result<Unit> =
        execTrusted(listOf("/system/bin/input", "keyevent", keyCode.toString())).checkExitCode().map { }

    companion object {
        /** Быстрая эвристика без shell: наличие бинарника su в PATH. */
        fun hasSuBinary(): Boolean =
            (System.getenv("PATH") ?: "").split(File.pathSeparator).any { dir ->
                File(dir, "su").canExecute()
            }
    }
}
