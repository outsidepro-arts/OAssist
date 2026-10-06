package ru.outsideproarts.oassist.core.device

import android.os.Binder
import android.os.Parcel

/**
 * Привилегированный процесс Shizuku (shell uid 2000 / root uid 0).
 *
 * Shizuku запускает этот класс в отдельном процессе из APK и создаёт экземпляр
 * по имени ([Shizuku.bindUserService]). Контекст приложения здесь недоступен:
 * только [ProcessBuilder] + чистая логика ([ShellPolicy]).
 *
 * Протокол — сырые транзакции Binder (без AIDL):
 * - [TRANSACTION_EXEC]: argv + таймаут + кап → exitCode + stdout + truncated.
 *   Строгий белый список [ShellPolicy.checkShellExec] проверяется и здесь.
 * - [TRANSACTION_PUT_SETTING]: namespace/key/value → `settings put`.
 * - [TRANSACTION_INPUT]: жест `input tap/swipe`.
 */
class PrivUserService : Binder() {

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val count = data.readInt().coerceIn(0, MAX_ARGS)
                val cmd = List(count) { data.readString().orEmpty() }
                val timeoutMs = data.readLong().coerceIn(1, ShellPolicy.MAX_TIMEOUT_MS)
                val maxBytes = data.readInt().coerceIn(1, ShellPolicy.MAX_BYTES)
                val result = runExec(cmd)
                val (capped, truncated) = ShellPolicy.capOutput(result.stdout, maxBytes)
                reply?.writeInt(result.exitCode)
                reply?.writeString(capped)
                reply?.writeInt(if (truncated) 1 else 0)
                true
            }
            TRANSACTION_PUT_SETTING -> {
                data.enforceInterface(DESCRIPTOR)
                val namespace = data.readString().orEmpty()
                val key = data.readString().orEmpty()
                val value = data.readString().orEmpty()
                val err = ShellPolicy.checkNamespace(namespace)
                    ?: if (key.isBlank()) "Пустой ключ" else null
                if (err != null) {
                    reply?.writeInt(-1)
                    reply?.writeString(err)
                } else {
                    val result = runRaw(
                        listOf("settings", "put", namespace, key, value),
                        ShellPolicy.DEFAULT_TIMEOUT_MS,
                    )
                    reply?.writeInt(result.exitCode)
                    reply?.writeString(result.stdout)
                }
                true
            }
            TRANSACTION_INPUT -> {
                data.enforceInterface(DESCRIPTOR)
                val kind = data.readString().orEmpty()
                val args = List(5) { data.readString().orEmpty() }.filter { it.isNotEmpty() }
                val cmd = listOf("input", kind) + args
                val result = runRaw(cmd, ShellPolicy.DEFAULT_TIMEOUT_MS)
                reply?.writeInt(result.exitCode)
                reply?.writeString(result.stdout)
                true
            }
            TRANSACTION_DESTROY, TRANSACTION_DESTROY + 1 -> {
                reply?.writeInt(0)
                reply?.writeString("bye")
                destroy()
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun runExec(cmd: List<String>): ExecResult {
        val denied = ShellPolicy.checkShellExec(cmd)
        if (denied != null) return ExecResult(-1, "Запрещено: $denied")
        return runRaw(cmd, ShellPolicy.DEFAULT_TIMEOUT_MS)
    }

    private fun runRaw(cmd: List<String>, timeoutMs: Long): ExecResult = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Команда превысила таймаут ${timeoutMs}мс")
        }
        ExecResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }.getOrElse { ExecResult(-1, "Ошибка: ${it.message}") }

    private fun destroy() {
        object : Thread() {
            override fun run() {
                try {
                    sleep(500)
                } catch (_: InterruptedException) {
                }
                System.exit(0)
            }
        }.start()
    }

    companion object {
        const val DESCRIPTOR = "ru.outsideproarts.oassist.core.device.PrivUserService"
        const val TRANSACTION_EXEC = android.os.IBinder.FIRST_CALL_TRANSACTION + 0
        const val TRANSACTION_PUT_SETTING = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_INPUT = android.os.IBinder.FIRST_CALL_TRANSACTION + 2

        /**
         * Код destroy по документации Shizuku: `16777115` (в aidl — `16777114`).
         * Обрабатываем оба.
         */
        const val TRANSACTION_DESTROY = 16777114

        private const val MAX_ARGS = 32
    }
}
