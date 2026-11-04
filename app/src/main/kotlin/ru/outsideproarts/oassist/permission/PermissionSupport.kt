package ru.outsideproarts.oassist.permission

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequester
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.feature.assistant.ConsentActivity

/**
 * Экран блокировки: `true` — экран заблокирован (пользователю нужно разблокировать,
 * чтобы ответить на диалог/выполнить действие).
 */
class KeyguardLockState(private val context: Context) {
    fun isLocked(): Boolean {
        val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return manager.isKeyguardLocked
    }
}

/**
 * Запрос runtime-разрешений через translucent [ConsentActivity]:
 * работает и из сервиса оверлея (с `FLAG_ACTIVITY_NEW_TASK`), и из чата.
 * Ожидание — через [PermissionRequestBridge.await] (fail-safe: без вечных зависаний,
 * с фолбэк-действием в уведомлении при заблокированном старте окна).
 */
class AppPermissionRequester(private val context: Context) : PermissionRequester {

    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun request(permissions: List<String>): PermissionRequestOutcome {
        val missing = permissions.filterNot(::isGranted)
        if (missing.isEmpty()) return PermissionRequestOutcome.GRANTED
        val requestId = PermissionRequestBridge.register(missing)
        try {
            val intent = ConsentActivity.permissionIntent(context, requestId, missing)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (!started) {
                // Старт невозможен в принципе: фолбэк-тап повторил бы тот же вызов.
                return PermissionRequestOutcome.UNAVAILABLE
            }
            val outcome = PermissionRequestBridge.await(requestId)
            if (outcome == PermissionRequestOutcome.UNAVAILABLE) {
                OAssistLog.warn(
                    TAG,
                    "Системный диалог разрешений не показан или не завершён: ${missing.joinToString()}",
                )
            }
            return outcome
        } finally {
            // Also covers failures before await takes ownership of the registered request.
            PermissionRequestBridge.cancel(requestId)
        }
    }

    private companion object {
        const val TAG = "PermissionRequester"
    }
}
