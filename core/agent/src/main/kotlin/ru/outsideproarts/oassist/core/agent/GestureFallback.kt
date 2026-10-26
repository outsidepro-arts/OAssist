package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun performGestureWithFallback(
    timeoutMs: Long,
    success: ToolResult,
    dispatch: (complete: (Boolean) -> Unit) -> Boolean,
    fallback: suspend () -> ToolResult,
): ToolResult {
    currentCoroutineContext().ensureActive()
    val completed = CompletableDeferred<Boolean>()
    val dispatched = try {
        dispatch { completed.complete(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        false
    }
    if (dispatched && withTimeoutOrNull(timeoutMs) { completed.await() } == true) return success
    currentCoroutineContext().ensureActive()
    return fallback()
}
