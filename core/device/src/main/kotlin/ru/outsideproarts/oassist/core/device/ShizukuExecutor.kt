package ru.outsideproarts.oassist.core.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcel
import rikka.shizuku.Shizuku
import ru.outsideproarts.oassist.core.model.PrivilegeLevel

/**
 * Shizuku-исполнитель: команды выполняются в [PrivUserService] с правами shell
 * (uid 2000) или root (uid 0) — см. [backendUid].
 *
 * M5.1: транспорт — UserService ([Shizuku.bindUserService], `newProcess` удалён
 * из API 13). Настройки — через команду `settings`, жесты — через `input`:
 * скрытые API следующим этапом. Без гранта/контекста — явная ошибка.
 */
class ShizukuExecutor(private val appContext: Context? = null) : PrivExecutor {
    override val level: PrivilegeLevel = PrivilegeLevel.SHIZUKU

    private val client: PrivServiceClient? by lazy {
        appContext?.let { PrivServiceClient(it.applicationContext) }
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shell-uid бэкенда: 0 — root, 2000 — adb. -1 если неизвестно. */
    fun backendUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun status(): PrivilegeStatus = PrivilegeStatus(
        shizukuAvailable = isBinderAlive(),
        shizukuGranted = isPermissionGranted(),
        rootAvailable = false,
    )

    private fun clientOrError(): Result<PrivServiceClient> {
        if (!isPermissionGranted()) {
            return Result.failure(SecurityException(SHIZUKU_DENIED_MESSAGE))
        }
        val c = client
        if (c == null) {
            return Result.failure(IllegalStateException("ShizukuExecutor без Context"))
        }
        return Result.success(c)
    }

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> {
        val c = clientOrError().getOrElse { return Result.failure(it) }
        return c.exec(
            cmd,
            ShellPolicy.coerceTimeout(timeoutMs),
            ShellPolicy.coerceMaxBytes(maxBytes),
        )
    }

    override suspend fun getSetting(namespace: String, key: String): Result<String?> {
        ShellPolicy.checkNamespace(namespace)?.let { return Result.failure(IllegalArgumentException(it)) }
        return exec(listOf("settings", "get", namespace, key)).map {
            it.stdout.trim().takeIf { v -> v != "null" }
        }
    }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
        ShellPolicy.checkNamespace(namespace)?.let { return Result.failure(IllegalArgumentException(it)) }
        val c = clientOrError().getOrElse { return Result.failure(it) }
        return c.transact(
            code = PrivUserService.TRANSACTION_PUT_SETTING,
            timeoutMs = ShellPolicy.DEFAULT_TIMEOUT_MS + 5_000L,
            write = {
                it.writeString(namespace)
                it.writeString(key)
                it.writeString(value)
            },
            read = { reply -> reply.readInt() to reply.readString().orEmpty() },
        ).mapCatching { (code, out) ->
            if (code != 0) throw IllegalStateException("settings put: $out") else Unit
        }
    }

    override suspend fun inputTap(x: Int, y: Int): Result<Unit> =
        input("tap", listOf(x.toString(), y.toString()))

    override suspend fun inputKeyevent(keyCode: Int): Result<Unit> =
        input("keyevent", listOf(keyCode.toString()))

    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> =
        input(
            "swipe",
            listOf(
                fromX.toString(), fromY.toString(), toX.toString(), toY.toString(),
                durationMs.coerceIn(1, 5000).toString(),
            ),
        )

    private suspend fun input(kind: String, args: List<String>): Result<Unit> {
        val c = clientOrError().getOrElse { return Result.failure(it) }
        return c.transact(
            code = PrivUserService.TRANSACTION_INPUT,
            timeoutMs = ShellPolicy.DEFAULT_TIMEOUT_MS + 5_000L,
            write = {
                it.writeString(kind)
                repeat(5) { i -> it.writeString(args.getOrNull(i).orEmpty()) }
            },
            read = { reply -> reply.readInt() to reply.readString().orEmpty() },
        ).mapCatching { (code, out) ->
            if (code != 0) throw IllegalStateException("input $kind: $out") else Unit
        }
    }

    fun close() {
        client?.close()
    }

    companion object {
        const val SHIZUKU_DENIED_MESSAGE =
            "Нет разрешения Shizuku: выдайте доступ в приложении Shizuku, затем повторите"

        /** Request-код для [Shizuku.requestPermission]. */
        const val REQUEST_CODE = 0x5A11
    }
}
