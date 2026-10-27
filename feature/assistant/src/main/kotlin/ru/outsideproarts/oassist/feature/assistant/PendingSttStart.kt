package ru.outsideproarts.oassist.feature.assistant

import android.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome

internal class PendingSttStart(private val scope: CoroutineScope) {
    private val lock = Any()
    private var owner: Any? = null
    private var job: Job? = null

    fun start(action: suspend (isCurrent: () -> Boolean) -> Unit) {
        synchronized(lock) {
            cancelLocked()
            val token = Any()
            // The owner must be installed before an immediate dispatcher can complete the job.
            val candidate = scope.launch(start = CoroutineStart.LAZY) {
                action { synchronized(lock) { owner === token } }
            }
            owner = token
            job = candidate
            candidate.invokeOnCompletion {
                synchronized(lock) {
                    if (owner === token) {
                        owner = null
                        job = null
                    }
                }
            }
            candidate.start()
        }
    }

    fun cancel() = synchronized(lock) { cancelLocked() }

    private fun cancelLocked() {
        val previous = job
        owner = null
        job = null
        previous?.cancel()
    }
}

internal suspend fun awaitBluetoothPermission(
    launch: (requestId: String, permissions: List<String>) -> Boolean,
): PermissionRequestOutcome? {
    val permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT)
    val requestId = PermissionRequestBridge.register(permissions)
    return try {
        if (!launch(requestId, permissions)) null
        else PermissionRequestBridge.await(requestId, userTimeoutMs = 60_000L)
    } finally {
        PermissionRequestBridge.cancel(requestId)
    }
}
