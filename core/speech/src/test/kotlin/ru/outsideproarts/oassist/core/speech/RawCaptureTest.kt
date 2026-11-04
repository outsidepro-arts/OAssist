package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RawCaptureTest {
    private class Source(val failStart: Boolean = false, val failStop: Boolean = false) : RawAudioSource {
        val releases = AtomicInteger()
        val stops = AtomicInteger()
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        override fun start() { if (failStart) error("start failed") }
        override fun read(buffer: ShortArray): Int {
            reading.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            buffer[0] = 42
            return 1
        }
        override fun stop() { stops.incrementAndGet(); if (failStop) error("stop failed") }
        override fun release() { releases.incrementAndGet() }
    }

    @Test fun startFailureReleasesExactlyOnceAndNextCaptureWorks() {
        val source = Source(failStart = true, failStop = true)
        val capture = RawCapture(source, 1, 1) { error("No worker started") }
        assertThrows(IllegalStateException::class.java) { capture.start() }
        assertTrue(capture.stop(100))
        assertEquals(1, source.releases.get())
        completesCapture(Source())
    }

    @Test fun stopFailureStillReleasesExactlyOnce() = completesCapture(Source(failStop = true))

    @Test fun naturalLimitKeepsExactBufferAndReleasesOnce() = completesCapture(Source())

    private fun completesCapture(source: Source) {
        val finished = CountDownLatch(1)
        val capture = RawCapture(source, 1, 1) { finished.countDown() }
        source.proceed.countDown()
        capture.start()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(capture.stop(1000))
        assertArrayEquals(shortArrayOf(42), capture.samples())
        capture.stop(1000)
        assertEquals(1, source.stops.get())
        assertEquals(1, source.releases.get())
    }

    @Test fun timedOutStopRetainsWorkerOwnershipAndLateReadCannotTouchNewBuffer() {
        val old = Source()
        val finished = CountDownLatch(1)
        val capture = RawCapture(old, 1, 16000) { finished.countDown() }
        capture.start()
        try {
            assertTrue(old.reading.await(5, TimeUnit.SECONDS))
            assertFalse(capture.stop(1))
            assertEquals(0, old.releases.get())
            assertThrows(IllegalStateException::class.java) { capture.samples() }
        } finally { old.proceed.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(capture.stop(1000))
        assertEquals(1, old.releases.get())
        completesCapture(Source())
        assertArrayEquals(shortArrayOf(42), capture.samples())
    }

    @Test fun readFailureReleasesAndNotifiesOwner() {
        val released = AtomicInteger()
        val finished = CountDownLatch(1)
        val source = object : RawAudioSource {
            override fun start() = Unit
            override fun read(buffer: ShortArray): Int = error("read failed")
            override fun stop() = Unit
            override fun release() { released.incrementAndGet() }
        }
        val capture = RawCapture(source, 1, 16000) { finished.countDown() }
        capture.start()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(capture.stop(1000))
        assertEquals(1, released.get())
        assertEquals(0, capture.samples().size)
    }
}
