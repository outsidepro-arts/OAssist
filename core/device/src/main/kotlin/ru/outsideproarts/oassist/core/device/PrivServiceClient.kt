package ru.outsideproarts.oassist.core.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * Клиент [PrivUserService]: ленивый bind, один коннект на процесс, сырые транзакции.
 * Все вызовы — с таймаутами; потеря сервиса сбрасывает коннект (следующий вызов
 * переподключится). Парцелы — в try/finally.
 */
internal class PrivServiceClient(private val appContext: Context) {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, PrivUserService::class.java.name),
    ).tag(SERVICE_TAG).version(SERVICE_VERSION).daemon(false).processNameSuffix("priv")

    private val lock = Mutex()
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null

    suspend fun service(): Result<IBinder> {
        binder?.takeIf { it.isBinderAlive() }?.let { return Result.success(it) }
        return lock.withLock {
            binder?.takeIf { it.isBinderAlive() }?.let { return Result.success(it) }
            connectLocked()
        }
    }

    private suspend fun connectLocked(): Result<IBinder> {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return Result.failure(IllegalStateException("Shizuku не запущен"))
        }
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) connected.complete(service)
                else connected.completeExceptionally(IllegalStateException("Пустой binder сервиса"))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (lock.tryLock()) {
                    try {
                        if (binder?.isBinderAlive() == false) binder = null
                    } finally {
                        lock.unlock()
                    }
                }
            }
        }
        // bindUserService — как в демо Shizuku, с главного потока. Возвращает Unit,
        // при мёртвом биндере бросает IllegalStateException.
        val bindError = withContext(Dispatchers.Main) {
            runCatching { Shizuku.bindUserService(args, conn) }.exceptionOrNull()
        }
        if (bindError != null) {
            return Result.failure(
                IllegalStateException("Не удалось привязать привилегированный сервис", bindError),
            )
        }
        connection = conn
        return runCatching {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }.also { binder = it }
        }.onFailure {
            runCatching { Shizuku.unbindUserService(args, conn, false) }
            connection = null
        }
    }

    /**
     * Сырая транзакция: [write] наполняет запрос (после interface token),
     * [read] разбирает ответ. Выполняется на IO с общим таймаутом.
     */
    suspend fun <T> transact(
        code: Int,
        timeoutMs: Long,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): Result<T> {
        val target = service().getOrElse { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(timeoutMs) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(PrivUserService.DESCRIPTOR)
                        write(data)
                        target.transact(code, data, reply, 0)
                        read(reply)
                    } finally {
                        reply.recycle()
                        data.recycle()
                    }
                }
            }.onFailure {
                if (!target.isBinderAlive && lock.tryLock()) {
                    try {
                        if (binder === target) binder = null
                    } finally {
                        lock.unlock()
                    }
                }
            }
        }
    }

    suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> =
        transact(
            code = PrivUserService.TRANSACTION_EXEC,
            timeoutMs = timeoutMs + IPC_SLACK_MS,
            write = {
                it.writeInt(cmd.size)
                cmd.forEach { arg -> it.writeString(arg) }
                it.writeLong(timeoutMs)
                it.writeInt(maxBytes)
            },
            read = {
                ExecResult(
                    exitCode = it.readInt(),
                    stdout = it.readString().orEmpty(),
                    truncated = it.readInt() != 0,
                )
            },
        )

    fun close() {
        val conn = connection ?: return
        connection = null
        binder = null
        runCatching { Shizuku.unbindUserService(args, conn, false) }
    }

    companion object {
        /** Тег и версия сервиса: смена версии перезапускает сервис. */
        const val SERVICE_TAG = "oassist-priv-v1"
        const val SERVICE_VERSION = 1

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val IPC_SLACK_MS = 5_000L
    }
}
