package ru.outsideproarts.oassist.core.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM → WAV 16 кГц моно 16 бит: формат голосовых ходов для моделей
 * (OpenAI `input_audio` wav, Gemini `inline_data` audio/wav).
 * Чистая функция, JVM-тестируема.
 */
object WavEncoder {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_SIZE = 44

    fun pcmToWav(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteBuffer.allocate(HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataSize)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1) // PCM
        out.putShort(CHANNELS.toShort())
        out.putInt(sampleRate)
        out.putInt(sampleRate * CHANNELS * BITS_PER_SAMPLE / 8)
        out.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
        out.putShort(BITS_PER_SAMPLE.toShort())
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataSize)
        pcm.forEach { out.putShort(it) }
        return out.array()
    }

    /** Длительность целых секунд (для плейсхолдера пузыря и AudioPart). */
    fun durationSec(sampleCount: Int, sampleRate: Int = SAMPLE_RATE): Int =
        if (sampleCount <= 0) 0 else sampleCount / sampleRate
}
