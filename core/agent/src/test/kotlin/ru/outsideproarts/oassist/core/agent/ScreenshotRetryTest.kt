package ru.outsideproarts.oassist.core.agent

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ретраи троттлинга платформы. Фейковый бэкенд повторяет семантику AOSP
 * (`AbstractAccessibilityServiceConnection`): сравнение «<= 333 мс», метку времени
 * обновляет запрос, прошедший проверку. Именно троттлинг давал «код ошибки 3» при двух
 * снимках подряд (например, инструмент вслед за инструментом после чёрного кадра шторки).
 */
class ScreenshotRetryTest {

    private class ThrottledCapture {
        var now = 0L
        private var last: Long? = null
        var attempts = 0

        fun request(): Result<Int> {
            attempts++
            val previous = last
            if (previous != null && now - previous <= INTERVAL_MS) {
                return Result.failure(
                    ScreenshotException(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT),
                )
            }
            last = now
            return Result.success(attempts)
        }

        companion object {
            private const val INTERVAL_MS = 333L
        }
    }

    @Test
    fun `два быстрых запроса без ретрая — второй упирается в троттлинг платформы`() = runBlocking {
        val capture = ThrottledCapture()
        assertTrue(capture.request().isSuccess)
        val second = capture.request()
        assertTrue(second.isFailure)
        assertEquals(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            (second.exceptionOrNull() as ScreenshotException).code,
        )
    }

    @Test
    fun `ретрай переживает троттлинг и добивается кадра`() = runBlocking {
        val capture = ThrottledCapture()
        capture.now = 100
        assertTrue(capture.request().isSuccess) // «сжигает» окно интервала
        val waits = mutableListOf<Long>()
        val result = withScreenshotRetry(wait = { ms -> waits += ms; capture.now += ms }) {
            capture.request()
        }
        assertTrue(result.isSuccess)
        assertEquals(2 + 1, capture.attempts) // 2 внутри ретрая + 1 setup-запрос
        // Задержка с запасом: ровно 333 мс недостаточно (семантика «<=»).
        assertEquals(listOf(SCREENSHOT_RETRY_DELAY_MS), waits)
        assertTrue(waits.single() > 333L)
    }

    @Test
    fun `успешный снимок не ждёт и не повторяется`() = runBlocking {
        val capture = ThrottledCapture()
        val waits = mutableListOf<Long>()
        val result = withScreenshotRetry(wait = { ms -> waits += ms; capture.now += ms }) {
            capture.request()
        }
        assertTrue(result.isSuccess)
        assertEquals(1, capture.attempts)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `ретраится только троттлинг — другие коды не повторяются`() = runBlocking {
        val waits = mutableListOf<Long>()
        val result = withScreenshotRetry(wait = { ms -> waits += ms }) {
            Result.failure<Int>(ScreenshotException(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW))
        }
        assertTrue(result.isFailure)
        assertEquals(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW,
            (result.exceptionOrNull() as ScreenshotException).code,
        )
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `чужая ошибка не ретраится`() = runBlocking {
        val waits = mutableListOf<Long>()
        val result = withScreenshotRetry(wait = { ms -> waits += ms }) {
            Result.failure<Int>(IllegalStateException("boom"))
        }
        assertTrue(result.isFailure)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `после исчерпания попыток возвращается ошибка троттлинга`() = runBlocking {
        val capture = ThrottledCapture()
        capture.now = 100
        assertTrue(capture.request().isSuccess)
        val waits = mutableListOf<Long>()
        // Ожидание не двигает время назад — платформа продолжает отбивать запросы.
        val result = withScreenshotRetry(wait = { ms -> waits += ms }) { capture.request() }
        assertTrue(result.isFailure)
        assertEquals(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            (result.exceptionOrNull() as ScreenshotException).code,
        )
        assertEquals(SCREENSHOT_RETRY_MAX + 1 + 1, capture.attempts) // 3 захвата внутри ретрая + 1 setup
        assertEquals(SCREENSHOT_RETRY_MAX, waits.size)
    }
}
