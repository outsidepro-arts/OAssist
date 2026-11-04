package ru.outsideproarts.oassist.core.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Bounds client waiting, not the lifetime of a synchronous Binder call.
 * The worker owns the slot and all operation resources until it actually returns.
 * Waiting callers are cancellable; one stuck Binder cannot exhaust the IO pool.
 */
internal class BinderDeadline(
    private val executor: Executor = Executor { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) },
) {
    private val slot = Mutex()

    suspend fun <T> await(timeoutMs: Long, operation: () -> T): T = withTimeout(timeoutMs) {
        slot.lock()
        suspendCancellableCoroutine { continuation ->
            try {
                executor.execute {
                    try {
                        // Cancellation before dispatch must not send a stale request.
                        if (continuation.isActive) {
                            continuation.resumeWith(runCatching(operation))
                        }
                    } finally {
                        slot.unlock()
                    }
                }
            } catch (failure: Exception) {
                slot.unlock()
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }
}
