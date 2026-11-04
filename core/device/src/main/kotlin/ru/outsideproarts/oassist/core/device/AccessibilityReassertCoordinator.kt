package ru.outsideproarts.oassist.core.device

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/** Holds the owner until the dispatched operation actually completes, even if its caller stops waiting. */
class AccessibilityReassertCoordinator {
    private val owner = Mutex()

    suspend fun run(component: String, select: suspend () -> PrivExecutor): Result<Unit> {
        owner.lock()
        val released = AtomicBoolean()
        val release = { if (released.compareAndSet(false, true)) owner.unlock() }
        val executor = try { select() } catch (e: Throwable) { release(); throw e }
        return executor.reassertAccessibilityService(component, release)
    }
}
