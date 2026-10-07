package ru.outsideproarts.oassist.core.speech

/** Характер отказа старта распознавания. */
sealed interface SttFailure {
    /** Синхронный отказ [VoiceListener.startStt] (возвращённый [CaptureError]). */
    data class SyncStartFailed(val error: CaptureError) : SttFailure

    /** Асинхронный отказ движка (onError). */
    data class AsyncError(val error: SttError, val hadPartial: Boolean) : SttFailure
}

/** Решение о повторе старта распознавания. */
sealed interface SttStartDecision {
    data class Retry(val delayMs: Long) : SttStartDecision
    data object Fail : SttStartDecision
}

/**
 * Политика повторов старта STT: распознавание падает (`ERROR_AUDIO`/`ERROR_CLIENT`/биз),
 * когда в момент старта ещё звучит TTS (TalkBack, озвучка ответа). Мгновенный отказ
 * до первого partial лечится повтором с нарастающей задержкой; настоящие ошибки — нет.
 */
object SttStartPolicy {

    /** Всего попыток, включая первую. */
    const val MAX_ATTEMPTS = 4

    private val DELAYS_MS = listOf(300L, 600L, 1200L)

    /** [attempt] — номер попытки, которая только что провалилась (1-based). */
    fun decide(attempt: Int, failure: SttFailure): SttStartDecision {
        val retryable = when (failure) {
            is SttFailure.SyncStartFailed ->
                failure.error == CaptureError.BUSY || failure.error == CaptureError.INIT_FAILED
            is SttFailure.AsyncError ->
                failure.error == SttError.BUSY && !failure.hadPartial
        }
        if (!retryable || attempt >= MAX_ATTEMPTS) return SttStartDecision.Fail
        return SttStartDecision.Retry(DELAYS_MS[(attempt - 1).coerceIn(DELAYS_MS.indices)])
    }
}
