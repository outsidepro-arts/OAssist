package ru.outsideproarts.oassist.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.R
import ru.outsideproarts.oassist.core.agent.ForegroundRunner

/**
 * Держатель foreground-статуса для долгих операций агента (M5.3, тип `specialUse`).
 * Сам ничего не выполняет: счётчик ссылок от [AppForegroundRunner] держит
 * процесс живым, пока идёт гео-фикс или WebView-рендер. Никакого звука/бейджа —
 * канал минимальной важности, текст статический (без секретов и PII).
 */
class OAssistTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.fgs_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.fgs_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "oassist_tasks"
        private const val NOTIFICATION_ID = 0x0A51
    }
}

/**
 * [ForegroundRunner] поверх [OAssistTaskService]: acquire перед задачей,
 * release после (счётчик — вложенные операции корректны). Сервис без задач
 * не висит: последний release останавливает его.
 */
class AppForegroundRunner(private val appContext: Context) : ForegroundRunner {
    private val mutex = Mutex()
    private var refCount = 0

    override suspend fun <T> run(task: suspend () -> T): T {
        acquire()
        try {
            return task()
        } finally {
            release()
        }
    }

    private suspend fun acquire() = mutex.withLock {
        if (refCount == 0) {
            withContext(Dispatchers.Default) {
                runCatching {
                    appContext.startForegroundService(
                        Intent(appContext, OAssistTaskService::class.java),
                    )
                }
            }
        }
        refCount++
    }

    private suspend fun release() = mutex.withLock {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) {
            runCatching {
                appContext.stopService(Intent(appContext, OAssistTaskService::class.java))
            }
        }
    }
}
