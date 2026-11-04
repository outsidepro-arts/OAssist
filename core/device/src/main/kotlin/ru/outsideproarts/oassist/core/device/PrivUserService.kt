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
class PrivUserService internal constructor(
    private val executeProcess: (List<String>, Long, Int) -> ExecResult,
) : Binder() {

    constructor() : this(ProcessRunner::run)

    private val reassert = AccessibilityReassert(command = ProcessRunner::runToCompletion)

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_REASSERT_ACCESSIBILITY -> {
                data.enforceInterface(DESCRIPTOR)
                val component = data.readString().orEmpty()
                val result = runCatching { reassert.run(component) }
                result.exceptionOrNull()?.let { android.util.Log.e("OAssist", "Accessibility reassert failed", it) }
                reply?.writeInt(if (result.isSuccess) 0 else -1)
                reply?.writeString(result.exceptionOrNull()?.message.orEmpty())
                true
            }
            TRANSACTION_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val count = data.readInt().coerceIn(0, MAX_ARGS)
                val cmd = List(count) { data.readString().orEmpty() }
                val timeoutMs = data.readLong().coerceIn(1, ShellPolicy.MAX_TIMEOUT_MS)
                val maxBytes = data.readInt().coerceIn(1, ShellPolicy.MAX_BYTES)
                val result = runExec(cmd, timeoutMs, maxBytes)
                val (capped, truncated) = ShellPolicy.capOutput(result.stdout, maxBytes)
                reply?.writeInt(result.exitCode)
                reply?.writeString(capped)
                reply?.writeInt(if (result.truncated || truncated) 1 else 0)
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
                        listOf("/system/bin/settings", "put", namespace, key, value),
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
                val cmd = listOf("/system/bin/input", kind) + args
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

    private fun runExec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): ExecResult {
        val denied = ShellPolicy.checkShellExec(cmd)
        if (denied != null) return ExecResult(-1, "Запрещено: $denied")
        return runRaw(ShellPolicy.shellExecArgv(cmd), timeoutMs, maxBytes)
    }

    private fun runRaw(cmd: List<String>, timeoutMs: Long, maxBytes: Int = ShellPolicy.DEFAULT_MAX_BYTES): ExecResult =
        runCatching { executeProcess(cmd, timeoutMs, maxBytes) }
            .getOrElse { ExecResult(-1, "Ошибка: ${it.message}") }

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
        const val TRANSACTION_REASSERT_ACCESSIBILITY = android.os.IBinder.FIRST_CALL_TRANSACTION + 3

        /**
         * Код destroy по документации Shizuku: `16777115` (в aidl — `16777114`).
         * Обрабатываем оба.
         */
        const val TRANSACTION_DESTROY = 16777114

        private const val MAX_ARGS = 32
    }
}
