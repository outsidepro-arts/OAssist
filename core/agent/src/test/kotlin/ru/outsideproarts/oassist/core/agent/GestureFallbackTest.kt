package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureFallbackTest {
    private val success = ToolResult(true, "gesture")
    private val fallbackResult = ToolResult(true, "input")

    @Test
    fun completedGestureDoesNotRequestPrivileges() = runTest {
        var complete: (Boolean) -> Unit = {}
        var privilegedCalls = 0
        val result = async {
            performGestureWithFallback(100, success, { complete = it; true }) {
                privilegedCalls++
                fallbackResult
            }
        }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, privilegedCalls)
        complete(true)
        assertEquals(success, result.await())
        assertEquals(0, privilegedCalls)
    }

    @Test
    fun rejectedGestureFallsBackImmediately() = runTest {
        val calls = mutableListOf<String>()
        val result = performGestureWithFallback(100, success, { calls += "gesture"; false }) {
            calls += "input"
            fallbackResult
        }
        assertEquals(fallbackResult, result)
        assertEquals(listOf("gesture", "input"), calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun cancelledGestureFallsBack() = runTest {
        val result = performGestureWithFallback(100, success, { it(false); true }) { fallbackResult }
        assertEquals(fallbackResult, result)
    }

    @Test
    fun timedOutGestureFallsBackOnceAndIgnoresLateCallback() = runTest {
        var complete: (Boolean) -> Unit = {}
        var privilegedCalls = 0
        val result = performGestureWithFallback(100, success, { complete = it; true }) {
            privilegedCalls++
            fallbackResult
        }
        assertEquals(fallbackResult, result)
        assertEquals(100L, testScheduler.currentTime)
        complete(true)
        complete(false)
        assertEquals(1, privilegedCalls)
    }

    @Test
    fun dispatchExceptionFallsBack() = runTest {
        val result = performGestureWithFallback(100, success, { throw SecurityException("denied") }) {
            fallbackResult
        }
        assertEquals(fallbackResult, result)
    }

    @Test
    fun failedPrivilegedFallbackIsReported() = runTest {
        val failure = ToolResult(false, "no privileges")
        assertEquals(failure, performGestureWithFallback(100, success, { false }) { failure })
    }

    @Test
    fun cancellingTaskDoesNotInjectAnotherTouch() = runTest {
        val started = CompletableDeferred<Unit>()
        var privilegedCalls = 0
        val result = async {
            performGestureWithFallback(100, success, { started.complete(Unit); true }) {
                privilegedCalls++
                fallbackResult
            }
        }
        started.await()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertEquals(0, privilegedCalls)
    }
}
