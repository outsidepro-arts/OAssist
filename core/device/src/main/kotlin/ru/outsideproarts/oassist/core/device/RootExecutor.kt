package ru.outsideproarts.oassist.core.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
class RootExecutor : PrivExecutor {
    override val level: PrivilegeLevel = PrivilegeLevel.ROOT

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val which = ProcessBuilder("which", "su")
                .start().apply { waitFor() }.inputStream.bufferedReader().readText().trim()
            if (which.isBlank()) return@runCatching false
            val probe = execRaw(listOf("su", "-c", "id"), ShellPolicy.DEFAULT_TIMEOUT_MS)
                .getOrNull() ?: return@runCatching false
            probe.exitCode == 0 && "uid=0" in probe.stdout
        }.getOrDefault(false)
    }

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> =
        withContext(Dispatchers.IO) {
            val flat = cmd.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
            execRaw(listOf("su", "-c", flat), ShellPolicy.coerceTimeout(timeoutMs))
                .map { (code, out) ->
                    val (capped, truncated) = ShellPolicy.capOutput(out, ShellPolicy.coerceMaxBytes(maxBytes))
                    ExecResult(code, capped, truncated)
                }
        }

    private fun execRaw(cmd: List<String>, timeoutMs: Long): Result<ExecResult> = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Команда превысила таймаут ${timeoutMs}мс")
        }
        val out = process.inputStream.bufferedReader().readText()
        ExecResult(process.exitValue(), out)
    }

    override suspend fun getSetting(namespace: String, key: String): Result<String?> {
        ShellPolicy.checkNamespace(namespace)?.let { return Result.failure(IllegalArgumentException(it)) }
        return exec(listOf("settings", "get", namespace, key)).map { it.stdout.trim().takeIf { v -> v != "null" } }
    }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
        ShellPolicy.checkNamespace(namespace)?.let { return Result.failure(IllegalArgumentException(it)) }
        return exec(listOf("settings", "put", namespace, key, value)).map { }
    }

    override suspend fun inputTap(x: Int, y: Int): Result<Unit> =
        exec(listOf("input", "tap", x.toString(), y.toString())).map { }

    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> =
        exec(
            listOf(
                "input", "swipe",
                fromX.toString(), fromY.toString(), toX.toString(), toY.toString(),
                durationMs.coerceIn(1, 5000).toString(),
            ),
        ).map { }

    override suspend fun inputKeyevent(keyCode: Int): Result<Unit> =
        exec(listOf("input", "keyevent", keyCode.toString())).map { }

    companion object {
        /** Быстрая эвристика без shell: наличие бинарника su в PATH. */
        fun hasSuBinary(): Boolean =
            (System.getenv("PATH") ?: "").split(File.pathSeparator).any { dir ->
                File(dir, "su").canExecute()
            }
    }
}
