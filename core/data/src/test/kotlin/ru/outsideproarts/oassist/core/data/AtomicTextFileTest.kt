package ru.outsideproarts.oassist.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.outsideproarts.oassist.core.data.memory.FileMemoryStore
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AtomicTextFileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedWritePreservesOldFileAndRetrySucceeds() {
        val file = temp.newFile("SKILL.md")
        file.writeText("old")
        assertThrows(IOException::class.java) {
            atomicWrite(file) { output ->
                output.write("partial".toByteArray())
                throw IOException("write failed")
            }
        }
        assertEquals("old", file.readText())
        atomicWriteText(file, "new")
        assertEquals("new", file.readText())
    }

    @Test fun readFailureDoesNotOverwriteMemoryOnAppend() = runBlocking {
        val directory = temp.newFolder()
        val memory = FileMemoryStore(directory)
        val invalidMemory = java.io.File(directory, "MEMORY.md")
        invalidMemory.mkdir()
        assertThrows(IOException::class.java) { runBlocking { memory.append("new") } }
        assertEquals(true, invalidMemory.isDirectory)
    }

    @Test fun concurrentAppendsSurviveReopen() = runBlocking {
        val directory = temp.newFolder()
        val stores = List(2) { FileMemoryStore(directory) }
        val start = CountDownLatch(1)
        val jobs = (1..50).map { number ->
            async(Dispatchers.IO) {
                start.await()
                stores[number % 2].append("line $number")
            }
        }
        start.countDown()
        jobs.awaitAll()
        val lines = FileMemoryStore(directory).read().lines()
        assertEquals((1..50).map { "line $it" }.toSet(), lines.toSet())
        assertEquals(50, lines.size)
    }

    @Test fun secondAppendWaitsForFirstWriteBeforeReading() = runBlocking {
        val directory = temp.newFolder()
        val firstWriting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondWriting = CountDownLatch(1)
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val first = FileMemoryStore(directory) { file, text ->
            firstThread.set(Thread.currentThread())
            firstWriting.countDown()
            check(releaseFirst.await(30, TimeUnit.SECONDS))
            atomicWriteText(file, text)
        }
        val second = FileMemoryStore(directory) { file, text ->
            secondWriting.countDown()
            atomicWriteText(file, text)
        }
        val one = async(Dispatchers.IO) { first.append("first") }
        try {
            check(firstWriting.await(5, TimeUnit.SECONDS))
            val two = async(Dispatchers.IO) {
                secondThread.set(Thread.currentThread())
                secondStarted.countDown()
                second.append("second")
            }
            check(secondStarted.await(5, TimeUnit.SECONDS))
            val threadBean = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var info = threadBean.getThreadInfo(secondThread.get().id)
            while (info?.threadState != Thread.State.BLOCKED && secondWriting.count != 0L &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
                info = threadBean.getThreadInfo(secondThread.get().id)
            }
            assertTrue("second append did not block before writing", info?.threadState == Thread.State.BLOCKED)
            assertEquals(firstThread.get().id, info!!.lockOwnerId)
            releaseFirst.countDown()
            one.await()
            two.await()
            assertEquals("first\nsecond", FileMemoryStore(directory).read())
        } finally {
            releaseFirst.countDown()
        }
    }
}
