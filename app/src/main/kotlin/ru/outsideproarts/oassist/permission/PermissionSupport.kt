package ru.outsideproarts.oassist.permission

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequester
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
 * Ожидание — через [PermissionRequestBridge].
 */
class AppPermissionRequester(private val context: Context) : PermissionRequester {

    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun request(permissions: List<String>): PermissionRequestOutcome {
        val missing = permissions.filterNot(::isGranted)
        if (missing.isEmpty()) return PermissionRequestOutcome.GRANTED
        val (requestId, deferred) = PermissionRequestBridge.register()
        val intent = ConsentActivity.permissionIntent(context, requestId, missing)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { deferred.await() },
                onFailure = {
                    PermissionRequestBridge.cancel(requestId)
                    PermissionRequestOutcome.UNAVAILABLE
                },
            )
    }
}
