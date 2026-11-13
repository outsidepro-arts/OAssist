package ru.outsideproarts.oassist.core.data.diagnostics

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityLatchTest {

    @Test
    fun `probe repeats while unavailable and latches after first success`() {
        val calls = AtomicInteger()
        val answers = ArrayDeque(listOf(false, false, true, true))
        val latch = AvailabilityLatch { calls.incrementAndGet(); answers.removeFirst() }

        assertFalse(latch())
        assertFalse(latch())
        assertEquals(2, calls.get())
        assertTrue(latch())
        // Успех кэшируется: проба больше не выполняется.
        assertTrue(latch())
        assertTrue(latch())
        assertEquals(3, calls.get())
    }
}
