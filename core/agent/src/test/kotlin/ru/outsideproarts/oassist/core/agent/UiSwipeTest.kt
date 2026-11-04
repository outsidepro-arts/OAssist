package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.device.PrivExecutor

@OptIn(ExperimentalCoroutinesApi::class)
class UiSwipeTest {
    private class Fixture {
        var fallbacks = 0
        var callback: ((Boolean) -> Unit)? = null
        var dispatched = true
        var duration = 0L
        private val executor = object : PrivExecutor by NoopExecutor() {
            override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> {
                fallbacks++
                return Result.success(Unit)
            }
        }
        val ui = UiActions(serviceProvider = { null }, privProvider = { executor }).apply {
            dispatchSwipe = { _, _, _, _, millis, complete ->
                duration = millis
                callback = complete
                Result.success(dispatched)
            }
        }
    }

    @Test
    fun rejectedDispatchUsesFallback() = runTest {
        val f = Fixture().apply { dispatched = false }
        assertTrue(f.ui.swipe(1, 2, 3, 4, 500).success)
        assertEquals(1, f.fallbacks)
    }

    @Test
    fun completionAfterGestureDurationSucceedsWithoutFallback() = runTest {
        val f = Fixture()
        val result = async { f.ui.swipe(1, 2, 3, 4, 5000) }
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        assertFalse(result.isCompleted)
        f.callback!!(true)
        assertTrue(result.await().success)
        assertEquals(5000L, f.duration)
        assertEquals(0, f.fallbacks)
    }

    @Test
    fun timeoutAndLateCallbackNeverUseFallback() = runTest {
        val f = Fixture()
        val result = async { f.ui.swipe(1, 2, 3, 4, 5000) }
        runCurrent()
        advanceTimeBy(10001)
        runCurrent()
        val timedOut = result.await()
        assertFalse(timedOut.success)
        assertTrue(timedOut.output.contains("неизвестен"))
        f.callback!!(true)
        runCurrent()
        assertEquals(0, f.fallbacks)
    }

    @Test
    fun cancelledGestureDoesNotRepeat() = runTest {
        val f = Fixture()
        val result = async { f.ui.swipe(1, 2, 3, 4, 100) }
        runCurrent()
        f.callback!!(false)
        assertFalse(result.await().success)
        assertEquals(0, f.fallbacks)
    }

    @Test
    fun cancelledCallerAndLateCallbackDoNotRepeat() = runTest {
        val f = Fixture()
        val result = async { f.ui.swipe(1, 2, 3, 4, 100) }
        runCurrent()
        result.cancelAndJoin()
        f.callback!!(true)
        runCurrent()
        assertEquals(0, f.fallbacks)
    }
}
