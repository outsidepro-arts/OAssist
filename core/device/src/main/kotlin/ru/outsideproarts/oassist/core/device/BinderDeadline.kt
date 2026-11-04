package ru.outsideproarts.oassist.core.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
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

    suspend fun <T> await(timeoutMs: Long, onCompletion: () -> Unit = {}, onNotDispatched: () -> Unit = {}, operation: () -> T): T {
        val state = AtomicInteger(0)
        fun complete(expected: Int) {
            if (state.compareAndSet(expected, 2)) {
                if (expected == 0) onNotDispatched()
                onCompletion()
            }
        }
        try {
            return withTimeout(timeoutMs) {
                slot.lock()
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { complete(0) }
                    try {
                        executor.execute {
                            try {
                                // Cancellation before dispatch must not send a stale request.
                                if (state.compareAndSet(0, 1) && continuation.isActive) {
                                    continuation.resumeWith(runCatching(operation))
                                }
                            } finally {
                                slot.unlock()
                                complete(1)
                            }
                        }
                    } catch (failure: Exception) {
                        slot.unlock()
                        complete(0)
                        continuation.resumeWith(Result.failure(failure))
                    }
                }
            }
        } finally {
            complete(0)
        }
    }
}
