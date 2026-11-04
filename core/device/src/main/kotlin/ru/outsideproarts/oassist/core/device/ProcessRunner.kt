package ru.outsideproarts.oassist.core.device

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The caller owns the direct child; descendants may outlive it. */
internal object ProcessRunner {
    fun run(cmd: List<String>, timeoutMs: Long, maxChars: Int): ExecResult =
        runProcess(ProcessBuilder(cmd).redirectErrorStream(true).start(), timeoutMs, maxChars)

    internal fun runProcess(process: Process, timeoutMs: Long, maxChars: Int): ExecResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val output = FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val saved = StringBuilder(minOf(maxChars, 8192))
                val buffer = CharArray(8192)
                var truncated = false
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val keep = minOf(count, maxChars - saved.length)
                    saved.append(buffer, 0, keep)
                    if (keep < count) truncated = true
                }
                saved.toString() to truncated
            }
        }
        Thread(output, "priv-process-output").apply { isDaemon = true }.start()
        try {
            process.outputStream.close()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException()
            val (text, truncated) = output.get(remaining, TimeUnit.NANOSECONDS)
            return ExecResult(process.exitValue(), text, truncated)
        } catch (e: TimeoutException) {
            throw IllegalStateException("Команда превысила таймаут ${timeoutMs}мс", e)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            if (!output.isDone) {
                // Closing a pipe can itself block while a descendant still holds it.
                Thread({ runCatching { process.inputStream.close() } }, "priv-process-close")
                    .apply { isDaemon = true }.start()
            }
        }
    }
}
