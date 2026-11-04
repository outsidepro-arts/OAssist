package ru.outsideproarts.oassist.core.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityReassertTest {
    private val component = "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService"
    private val other = "other/.Service"

    @Test
    fun lateDisableAfterClientAndQueueTimeoutStillRestores() = runTest {
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restored = CountDownLatch(1)
        var value = "$component:$other"
        var puts = 0
        val owner = AccessibilityReassert(settle = {}) { cmd ->
            if (cmd[1] == "get") ExecResult(0, value)
            else {
                puts++
                if (puts == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                value = cmd.last()
                if (puts == 1) value += ":new/.Service" else restored.countDown()
                ExecResult(0, "")
            }
        }
        val deadline = BinderDeadline(pool)
        var firstNotDispatched = false
        var queuedNotDispatched = false
        try {
            val first = async { runCatching { deadline.await(100, onNotDispatched = { firstNotDispatched = true }) { owner.run(component) } } }
            kotlinx.coroutines.yield()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()
            assertTrue(first.await().isFailure)
            assertFalse(firstNotDispatched)
            val queued = async { runCatching { deadline.await(100, onNotDispatched = { queuedNotDispatched = true }) { error("must not dispatch") } } }
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()
            assertTrue(queued.await().isFailure)
            assertTrue(queuedNotDispatched)
            release.countDown()
            assertTrue(restored.await(5, TimeUnit.SECONDS))
            assertEquals("$other:new/.Service:$component", value)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun failedDisableAfterMutationStillRestores() {
        var value = "$component:$other"
        var puts = 0
        val owner = AccessibilityReassert(settle = {}) { cmd ->
            if (cmd[1] == "get") ExecResult(0, value) else {
                value = cmd.last()
                puts++
                ExecResult(if (puts == 1) 1 else 0, "failure")
            }
        }
        assertTrue(runCatching { owner.run(component) }.isFailure)
        assertEquals("$other:$component", value)
    }

    @Test
    fun restoreFailureIsReported() {
        var puts = 0
        val owner = AccessibilityReassert(settle = {}) { cmd ->
            if (cmd[1] == "get") ExecResult(0, if (puts == 0) component else "")
            else ExecResult(if (++puts == 2) 1 else 0, "restore failed")
        }
        assertTrue(runCatching { owner.run(component) }.exceptionOrNull()?.message?.contains("restore failed") == true)
    }

    @Test
    fun coordinatorDoesNotSelectAnotherBackendUntilLateWorkerFinishes() = runTest {
        repeat(2) {
            val pool = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val deadline = BinderDeadline(pool)
            val coordinator = AccessibilityReassertCoordinator()
            var selections = 0
            var value = "$component:$other"
            var puts = 0
            val backendOwner = AccessibilityReassert(settle = {}) { cmd ->
                if (cmd[1] == "get") ExecResult(0, value) else {
                    if (++puts == 1) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    value = cmd.last()
                    ExecResult(0, "")
                }
            }
            val firstBackend = object : PrivExecutor by NoopExecutor() {
                override suspend fun reassertAccessibilityService(component: String, onCompletion: () -> Unit): Result<Unit> = runCatching {
                    deadline.await(100, onCompletion) {
                        backendOwner.run(component)
                        finished.countDown()
                    }
                }
            }
            var secondCalled = false
            val secondBackend = object : PrivExecutor by NoopExecutor() {
                override suspend fun reassertAccessibilityService(component: String, onCompletion: () -> Unit): Result<Unit> {
                    secondCalled = true
                    onCompletion()
                    return Result.success(Unit)
                }
            }
            try {
                val first = async { coordinator.run(component) { selections++; firstBackend } }
                kotlinx.coroutines.yield()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                testScheduler.advanceTimeBy(101)
                testScheduler.runCurrent()
                assertTrue(first.await().isFailure)
                val second = async { coordinator.run(component) { selections++; secondBackend } }
                testScheduler.runCurrent()
                assertEquals(1, selections)
                assertFalse(secondCalled)
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                while (!second.isCompleted) { kotlinx.coroutines.yield() }
                assertTrue(second.await().isSuccess)
                assertEquals(2, selections)
                assertTrue(secondCalled)
                assertEquals("$other:$component", value)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun cancellationBeforeDispatchReleasesOwnerWithoutRunningStaleOperation() = runTest {
        var queued: Runnable? = null
        val deadline = BinderDeadline { queued = it }
        val coordinator = AccessibilityReassertCoordinator()
        var dispatched = false
        val backend = object : PrivExecutor by NoopExecutor() {
            override suspend fun reassertAccessibilityService(component: String, onCompletion: () -> Unit): Result<Unit> = runCatching {
                deadline.await(1000, onCompletion) { dispatched = true }
            }
        }
        val first = async { coordinator.run(component) { backend } }
        testScheduler.runCurrent()
        assertNotNull(queued)
        first.cancel()
        testScheduler.runCurrent()
        var secondSelected = false
        coordinator.run(component) { secondSelected = true; NoopExecutor() }
        assertTrue(secondSelected)
        queued!!.run()
        assertFalse(dispatched)
    }
}
