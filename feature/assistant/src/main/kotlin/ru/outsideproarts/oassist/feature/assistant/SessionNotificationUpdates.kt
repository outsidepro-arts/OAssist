package ru.outsideproarts.oassist.feature.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread owner. Read current notification state only when publishing, never at request time. */
internal class SessionNotificationUpdates(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val publish: () -> Unit,
) {
    private var lastSentAt: Long? = null
    private var pending: Job? = null
    private var closed = false

    /** Mandatory foreground calls bypass scheduling, but count towards the next update interval. */
    fun foregroundSent() {
        lastSentAt = now()
    }

    fun request() {
        if (closed || pending != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A mandatory foreground call can move the deadline while this coroutine is asleep.
            while (true) {
                val remaining = lastSentAt?.let { MIN_INTERVAL_MS - (now() - it) } ?: 0L
                if (remaining <= 0) break
                delay(remaining)
            }
            pending = null
            lastSentAt = now()
            publish()
        }
        pending = job
        job.start()
    }

    fun close() {
        closed = true
        pending?.cancel()
        pending = null
    }

    private companion object {
        const val MIN_INTERVAL_MS = 500L
    }
}
