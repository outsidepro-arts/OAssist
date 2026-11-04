package ru.outsideproarts.oassist.core.device

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProcessRunnerTest {
    @Test fun drainsRealChildOutputLargerThanPipe() {
        val javaCommand = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes = java.io.File(OutputProcess::class.java.protectionDomain!!.codeSource.location.toURI()).path
        val result = ProcessRunner.run(listOf(javaCommand, "-cp", classes, OutputProcess::class.java.name), 5000, 1024)
        assertEquals(0, result.exitCode)
        assertEquals(1024, result.stdout.length)
        assertTrue(result.stdout.endsWith("x".repeat(100)))
        assertTrue(result.truncated)
    }

    @Test fun drainsPipeBeforeWaitingAndCapsStoredOutput() {
        val process = ControlledProcess()
        val writer = Thread {
            process.writeOutput("x".repeat(256 * 1024))
            process.complete()
        }.apply { start() }

        val result = ProcessRunner.runProcess(process, 5000, 100)

        writer.join(5000)
        assertFalse(writer.isAlive)
        assertEquals(0, result.exitCode)
        assertEquals("x".repeat(100), result.stdout)
        assertTrue(result.truncated)
    }

    @Test fun timeoutDestroysChildAndLongerTimeoutAllowsCompletion() {
        val short = ControlledProcess()
        val failure = runCatching { ProcessRunner.runProcess(short, 50, 100) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(short.destroyed)

        val long = ControlledProcess()
        val result = AtomicReference<ExecResult>()
        val runner = Thread { result.set(ProcessRunner.runProcess(long, 5000, 100)) }.apply { start() }
        assertTrue(long.waiting.await(2, TimeUnit.SECONDS))
        long.writeOutput("done")
        long.complete()
        runner.join(2000)
        assertFalse(runner.isAlive)
        assertEquals("done", result.get().stdout)
        assertFalse(result.get().truncated)
    }

    @Test fun interruptionDestroysChild() {
        val process = ControlledProcess()
        val failure = AtomicReference<Throwable>()
        val runner = Thread {
            try { ProcessRunner.runProcess(process, 5000, 100) } catch (e: Throwable) { failure.set(e) }
        }.apply { start() }
        assertTrue(process.waiting.await(2, TimeUnit.SECONDS))
        runner.interrupt()
        runner.join(2000)
        assertFalse(runner.isAlive)
        assertTrue(failure.get() is InterruptedException)
        assertTrue(process.destroyed)
    }

    private class ControlledProcess : Process() {
        private val pipe = PipedInputStream(1024)
        private val writer = PipedOutputStream(pipe)
        private val finished = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        @Volatile var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = pipe
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { finished.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waiting.countDown()
            return finished.await(timeout, unit)
        }
        override fun exitValue(): Int { check(finished.count == 0L); return 0 }
        override fun isAlive() = finished.count != 0L
        override fun destroy() { destroyed = true; writer.close(); finished.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        fun writeOutput(value: String) { writer.write(value.toByteArray()) }
        fun complete() { writer.close(); finished.countDown() }
    }
}
