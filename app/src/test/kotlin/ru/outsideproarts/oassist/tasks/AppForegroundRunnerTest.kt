package ru.outsideproarts.oassist.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AppForegroundRunnerTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runNext() { queue.removeFirst().run() }
    }

    @Test
    fun cancellationAfterStartBeforeAcquireReturnsStopsService() = runTest {
        val startDispatcher = PausedDispatcher()
        var starts = 0
        var stops = 0
        var taskRan = false
        val runner = AppForegroundRunner({ starts++ }, { stops++ }, startDispatcher)
        val job = launch { runner.run { taskRan = true } }

        runCurrent()
        startDispatcher.runNext()
        assertEquals(1, starts)
        job.cancel()
        runCurrent()

        assertFalse(taskRan)
        assertEquals(1, stops)
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancelledAcquireDoesNotStopAnotherOwner() = runTest {
        val startDispatcher = PausedDispatcher()
        var starts = 0
        var stops = 0
        val runner = AppForegroundRunner({ starts++ }, { stops++ }, startDispatcher)
        val finish = CompletableDeferred<Unit>()
        val first = launch { runner.run { } }
        runCurrent()
        val second = launch { runner.run { finish.await() } }
        runCurrent()
        startDispatcher.runNext()
        first.cancel()
        runCurrent()

        assertEquals(1, starts)
        assertEquals(0, stops)
        assertTrue(second.isActive)
        finish.complete(Unit)
        runCurrent()
        assertEquals(1, stops)
    }

    @Test
    fun cancellationAndFailureReleaseOwnershipAndAllowReopen() = runTest {
        var starts = 0
        var stops = 0
        val runner = AppForegroundRunner({ starts++ }, { stops++ }, StandardTestDispatcher(testScheduler))
        val job = launch { runner.run { CompletableDeferred<Unit>().await() } }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, stops)

        try {
            runner.run { error("task failure") }
            fail("task failure must propagate")
        } catch (_: IllegalStateException) {
            // The failure belongs to the task, but must not keep its service alive.
        }
        assertEquals(2, starts)
        assertEquals(2, stops)
    }

    @Test
    fun serviceErrorsDoNotLeakOwnership() = runTest {
        var starts = 0
        var stops = 0
        val runner = AppForegroundRunner(
            { starts++; error("start failure") },
            { stops++; error("stop failure") },
            StandardTestDispatcher(testScheduler),
        )
        runner.run { }
        runner.run { }
        assertEquals(2, starts)
        assertEquals(2, stops)
    }
}
