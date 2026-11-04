package ru.outsideproarts.oassist.core.speech

import kotlin.concurrent.thread

internal interface RawAudioSource {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}

/** One recorder, buffer and worker. Ownership transfers to the worker only after start succeeds. */
internal class RawCapture(
    private val source: RawAudioSource,
    private val bufferSize: Int,
    private val maxSamples: Int,
    private val onFinished: () -> Unit,
) {
    @Volatile var stopRequested = false
        private set
    private var worker: Thread? = null
    private val pcm = ArrayList<Short>()

    fun start() {
        try {
            source.start()
            worker = thread(start = false, name = "oassist-raw-capture", isDaemon = true) {
                try {
                    val buffer = ShortArray(bufferSize)
                    val deadline = System.nanoTime() + maxOf(1L, maxSamples.toLong() / WavEncoder.SAMPLE_RATE) * 1_000_000_000L
                    while (!stopRequested && pcm.size < maxSamples && System.nanoTime() < deadline) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        for (i in 0 until minOf(read, maxSamples - pcm.size)) pcm.add(buffer[i])
                        if (read == 0) Thread.sleep(10)
                    }
                } catch (_: Exception) {
                    // A failed read ends this capture; the owner still collects/discards its buffer.
                } finally {
                    dispose()
                    onFinished()
                }
            }.also { it.start() }
        } catch (e: Exception) {
            dispose()
            throw e
        }
    }

    private fun dispose() {
        try {
            runCatching { source.stop() }
        } finally {
            runCatching { source.release() }
        }
    }

    /** A timeout does not transfer ownership or permit another recorder to start. */
    fun stop(timeoutMs: Long): Boolean {
        stopRequested = true
        try {
            worker?.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return worker?.isAlive != true
    }

    fun samples(): ShortArray {
        check(worker?.isAlive != true)
        return pcm.toShortArray()
    }
}
