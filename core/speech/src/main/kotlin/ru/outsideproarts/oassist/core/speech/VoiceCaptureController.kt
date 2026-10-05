package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Захват микрофона для тулов агента (`voice_listen_start/stop`).
 * Режим — всегда STT: модели нужен текст ответа, а не байты
 * (байты — только PTT-холд человека в чате).
 *
 * Старт неблокирующий («слушаю»), стоп — блокирующий до финала/таймаута.
 * Все тексты — для модели (русские, как остальные тул-результаты).
 * `SpeechRecognizer` требует главный поток — переключаемся сами.
 */
interface VoiceCaptureController {
    /** Начать слушать. Возвращает статус для модели. */
    suspend fun startCapture(language: String, timeoutSec: Int): String

    /** Остановить и транскрибировать. Возвращает текст/диагноз для модели. */
    suspend fun stopCapture(): String
}

class AgentVoiceCapture(private val voice: VoiceListener) : VoiceCaptureController {

    private var pending: CompletableDeferred<String>? = null

    override suspend fun startCapture(language: String, timeoutSec: Int): String {
        if (pending != null) return "Уже слушаю — сначала остановите запись (voice_listen_stop)."
        val timeout = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        val error = withContext(Dispatchers.Main) {
            voice.startStt(language, object : SttListener {
                override fun onPartial(text: String) = Unit
                override fun onFinal(text: String) {
                    pending?.complete(text)
                    pending = null
                }

                override fun onSttError(error: SttError) {
                    pending?.complete(failureText(error))
                    pending = null
                }
            })
        }
        if (error != null) return failureText(error)
        val deferred = CompletableDeferred<String>()
        pending = deferred
        val heard = withTimeoutOrNull(timeout * 1000L) { deferred.await() }
        if (heard != null) return heard
        withContext(Dispatchers.Main) { voice.cancelStt() }
        pending = null
        return "Время вышло ($timeout с) — ответа не расслышано."
    }

    override suspend fun stopCapture(): String {
        val deferred = pending ?: return "Сейчас ничего не записывается."
        withContext(Dispatchers.Main) { voice.stopStt() }
        // Финал приходит колбэком; страховка — 10 с, дальше жёсткая отмена.
        val heard = withTimeoutOrNull(STOP_GRACE_MS) { deferred.await() }
        if (heard != null) return heard.ifBlank { SILENCE }
        withContext(Dispatchers.Main) { voice.cancelStt() }
        pending = null
        return "Не удалось распознать — тишина или ошибка."
    }

    companion object {
        const val MIN_TIMEOUT_SEC = 5
        const val MAX_TIMEOUT_SEC = 300
        const val STOP_GRACE_MS = 10_000L
        const val SILENCE = "(тишина — ответа не было)"

        fun failureText(error: CaptureError): String = when (error) {
            CaptureError.BUSY -> "Уже идёт запись."
            CaptureError.NO_RECOGNIZER -> "Распознавание речи недоступно на этом устройстве."
            CaptureError.NO_PERMISSION -> "Нет доступа к микрофону — попросите пользователя выдать его в разделе «Инструменты агента»."
            CaptureError.INIT_FAILED -> "Не удалось включить микрофон."
        }

        fun failureText(error: SttError): String = when (error) {
            SttError.NO_SPEECH -> SILENCE
            SttError.NETWORK -> "Распознавание не удалось — проверьте подключение к сети."
            SttError.UNKNOWN -> "Распознавание не удалось."
        }
    }
}
