package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface VoiceCaptureResult {
    data class Transcript(val text: String) : VoiceCaptureResult
    data object NoSpeech : VoiceCaptureResult
    data class Timeout(val seconds: Int) : VoiceCaptureResult
    data class StartFailed(val error: CaptureError) : VoiceCaptureResult
    data class RecognitionFailed(val error: SttError) : VoiceCaptureResult
    data object Cancelled : VoiceCaptureResult
    data object NotRecording : VoiceCaptureResult
}

/** Захват одного голосового ответа для агента. */
interface VoiceCaptureController {
    /** Дождаться окончательного результата распознавания либо таймаута. */
    suspend fun startCapture(language: String, timeoutSec: Int): VoiceCaptureResult

    /** Остановить активный агентский захват и дождаться его результата. */
    suspend fun stopCapture(): VoiceCaptureResult
}

internal interface AgentSttBackend {
    fun start(language: String, listener: SttListener): CaptureError?
    fun stop(listener: SttListener): Boolean
    fun cancel(listener: SttListener): Boolean
}

private class VoiceListenerBackend(private val voice: VoiceListener) : AgentSttBackend {
    override fun start(language: String, listener: SttListener): CaptureError? =
        voice.startStt(language, listener)

    override fun stop(listener: SttListener): Boolean = voice.stopStt(listener)
    override fun cancel(listener: SttListener): Boolean = voice.cancelStt(listener)
}

class AgentVoiceCapture internal constructor(
    private val backend: AgentSttBackend,
    private val dispatcher: CoroutineDispatcher,
) : VoiceCaptureController {
    constructor(voice: VoiceListener) : this(VoiceListenerBackend(voice), Dispatchers.Main.immediate)

    private class Capture {
        val result = CompletableDeferred<VoiceCaptureResult>()
        lateinit var listener: SttListener
    }

    private var active: Capture? = null

    override suspend fun startCapture(language: String, timeoutSec: Int): VoiceCaptureResult {
        var capture: Capture? = null
        try {
            return withContext(dispatcher) {
                if (active != null) return@withContext VoiceCaptureResult.StartFailed(CaptureError.BUSY)
                val current = Capture()
                capture = current
                current.listener = object : SttListener {
                    override fun onPartial(text: String) = Unit
                    override fun onFinal(text: String) {
                        complete(current, if (text.isBlank()) VoiceCaptureResult.NoSpeech else VoiceCaptureResult.Transcript(text))
                    }
                    override fun onSttError(error: SttError) {
                        complete(current, if (error == SttError.NO_SPEECH) VoiceCaptureResult.NoSpeech else VoiceCaptureResult.RecognitionFailed(error))
                    }
                    override fun onSttCancelled() {
                        complete(current, VoiceCaptureResult.Cancelled)
                    }
                }
                active = current
                backend.start(language, current.listener)?.let {
                    complete(current, VoiceCaptureResult.StartFailed(it))
                }
                val timeout = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
                val result = withTimeoutOrNull(timeout * 1000L) { current.result.await() }
                if (result != null) return@withContext result
                if (active === current) {
                    complete(current, VoiceCaptureResult.Timeout(timeout))
                    backend.cancel(current.listener)
                }
                current.result.await()
            }
        } finally {
            withContext(NonCancellable + dispatcher) {
                capture?.let { current ->
                    if (active === current) {
                        complete(current, VoiceCaptureResult.Cancelled)
                        backend.cancel(current.listener)
                    }
                }
            }
        }
    }

    override suspend fun stopCapture(): VoiceCaptureResult = withContext(dispatcher) {
        val current = active ?: return@withContext VoiceCaptureResult.NotRecording
        if (!backend.stop(current.listener)) {
            complete(current, VoiceCaptureResult.Cancelled)
            return@withContext current.result.await()
        }
        val result = withTimeoutOrNull(STOP_GRACE_MS) { current.result.await() }
        if (result != null) return@withContext result
        if (active === current) {
            complete(current, VoiceCaptureResult.Timeout((STOP_GRACE_MS / 1000L).toInt()))
            backend.cancel(current.listener)
        }
        current.result.await()
    }

    private fun complete(capture: Capture, result: VoiceCaptureResult) {
        if (active !== capture) return
        active = null
        capture.result.complete(result)
    }

    companion object {
        const val MIN_TIMEOUT_SEC = 5
        const val MAX_TIMEOUT_SEC = 300
        const val STOP_GRACE_MS = 10_000L
        const val SILENCE = "(тишина — ответа не было)"

        fun failureText(error: CaptureError): String = when (error) {
            CaptureError.BUSY -> "Уже идёт запись."
            CaptureError.NO_RECOGNIZER -> "Распознавание речи недоступно на этом устройстве."
            CaptureError.NO_PERMISSION -> "Нет доступа к микрофону — попросите пользователя выдать его в настройках приложения OAssist."
            CaptureError.INIT_FAILED -> "Не удалось включить микрофон."
        }

        fun failureText(error: SttError): String = when (error) {
            SttError.NO_SPEECH -> SILENCE
            SttError.NETWORK -> "Распознавание не удалось — проверьте подключение к сети."
            SttError.BUSY -> "Аудиовход занят другим звуком — повторите попытку."
            SttError.UNKNOWN -> "Распознавание не удалось."
        }
    }
}
