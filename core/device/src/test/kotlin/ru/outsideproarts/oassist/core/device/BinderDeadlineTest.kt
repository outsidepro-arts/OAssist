package ru.outsideproarts.oassist.core.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class BinderDeadlineTest {
    @Test fun blockedOperationTimesOutWithoutReleasingResourcesOrStartingQueuedCall() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cleaned = AtomicBoolean(false)
        val deadline = BinderDeadline(executor)
        try {
            val first = async {
                runCatching {
                    deadline.await(100) {
                        try {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            "late"
                        } finally { cleaned.set(true) }
                    }
                }
            }
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queuedRan = AtomicBoolean(false)
            val queued = async {
                runCatching { deadline.await(50) { queuedRan.set(true) } }
            }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertTrue(first.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(queued.await().exceptionOrNull() is TimeoutCancellationException)
            assertFalse(cleaned.get())
            assertFalse(queuedRan.get())
            release.countDown()
            executor.execute { finished.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(cleaned.get())
            assertFalse(queuedRan.get())
            val next = async { deadline.await(1000) { "new" } }
            runCurrent()
            val nextFinished = CountDownLatch(1)
            executor.execute { nextFinished.countDown() }
            assertTrue(nextFinished.await(5, TimeUnit.SECONDS))
            runCurrent()
            assertEquals("new", next.await())
            assertTrue(first.await().isFailure)
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellingActiveWaitDoesNotWaitForWorkerOrStartCancelledQueue() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val deadline = BinderDeadline(executor)
        try {
            val first = async {
                deadline.await(1000) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    error("late error")
                }
            }
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = async { deadline.await(1000) { fail("Cancelled request ran") } }
            runCurrent()
            first.cancel()
            queued.cancel()
            runCurrent()
            assertTrue(first.isCompleted)
            assertTrue(queued.isCompleted)
            release.countDown()
            executor.execute { finished.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(first.isCancelled)
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellationBeforeWorkerStartsSkipsOperationAndReleasesSlot() = runTest {
        val tasks = ArrayDeque<Runnable>()
        val deadline = BinderDeadline(Executor { tasks.add(it) })
        var ran = false
        val cancelled = async { deadline.await(100) { ran = true } }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        tasks.removeFirst().run()
        assertFalse(ran)
        val next = async { deadline.await(100) { "next" } }
        runCurrent()
        tasks.removeFirst().run()
        runCurrent()
        assertEquals("next", next.await())
    }

    @Test fun failureAndRejectedDispatchReleaseSlot() = runTest {
        var reject = true
        val deadline = BinderDeadline(Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException()
            it.run()
        })
        assertTrue(runCatching { deadline.await(100) { 1 } }.isFailure)
        reject = false
        assertTrue(runCatching { deadline.await(100) { error("remote failure") } }.isFailure)
        assertEquals(2, deadline.await(100) { 2 })
    }
}
