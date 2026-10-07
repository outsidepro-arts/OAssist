package ru.outsideproarts.oassist.core.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayList
import kotlin.concurrent.thread

/**
 * Захват микрофона: STT через системный SpeechRecognizer (текст наружу)
 * или RAW через AudioRecord → WAV (байты наружу для модели).
 * Один захват за раз (guard — [VoiceCaptureSession]); состояние — в [state]
 * для иконок/индикаторов UI. Все методы вызывать с главного потока.
 */
class VoiceListener(private val context: Context) {

    private val session = VoiceCaptureSession()
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun isSttAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---- STT ----

    private var recognizer: SpeechRecognizer? = null
    private val sttGate = SttCallbackGate()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null
    private var sttSession: SttSession? = null

    /** Одна пользовательская сессия распознавания со всеми её попытками старта. */
    private class SttSession(
        val language: String,
        val listener: SttListener,
        var attempt: Int = 1,
        var hadPartial: Boolean = false,
    )

    /**
     * Начать системное распознавание. Частичные результаты — в [listener],
     * финал — тоже (там же снимать [state]). Возвращает причину отказа.
     */
    fun startStt(language: String, listener: SttListener): CaptureError? {
        session.start(CaptureMode.STT)?.let { return it }
        if (!hasMicPermission()) {
            session.reset()
            return CaptureError.NO_PERMISSION
        }
        if (!isSttAvailable()) {
            session.reset()
            return CaptureError.NO_RECOGNIZER
        }
        val startSession = SttSession(language, listener)
        sttSession = startSession
        val error = startSttAttempt(startSession, isFirst = true)
        if (error != null) {
            sttSession = null
            session.reset()
            return error
        }
        return null
    }

    /**
     * Одна попытка старта движка. Повторяемые отказы (звук ещё занят TTS, см. [SttStartPolicy])
     * уходят в отложенный повтор; финальные — наружу через [SttListener.onSttError].
     */
    private fun startSttAttempt(startSession: SttSession, isFirst: Boolean): CaptureError? {
        startSession.hadPartial = false
        // Гасим колбэки предыдущего движка до его отмены: cancel/destroy шлёт запоздалые onError.
        val token = sttGate.newSession()
        return try {
            recognizer?.cancel()
            recognizer?.destroy()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, startSession.language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            val engine = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            engine.setRecognitionListener(
                SttProxy(
                    listener = startSession.listener,
                    isActive = { sttGate.isActive(token) },
                    onPartialSeen = { startSession.hadPartial = true },
                    onErrorIntercept = { error -> handleAsyncError(startSession, error) },
                    onDone = { finishSttSession() },
                ),
            )
            engine.startListening(intent)
            _state.value = CaptureState.Listening(CaptureMode.STT, System.currentTimeMillis())
            null
        } catch (e: Exception) {
            handleSyncError(startSession, CaptureError.INIT_FAILED, isFirst)
        }
    }

    /** true — ошибка поглощена (запланирован повтор); false — пробросить наружу. */
    private fun handleAsyncError(startSession: SttSession, error: SttError): Boolean {
        val decision = SttStartPolicy.decide(
            startSession.attempt,
            SttFailure.AsyncError(error, startSession.hadPartial),
        )
        return when (decision) {
            is SttStartDecision.Retry -> {
                startSession.attempt += 1
                scheduleRetry(startSession, decision.delayMs)
                true
            }
            SttStartDecision.Fail -> {
                sttSession = null
                false
            }
        }
    }

    private fun handleSyncError(
        startSession: SttSession,
        error: CaptureError,
        isFirst: Boolean,
    ): CaptureError? {
        val decision = SttStartPolicy.decide(
            startSession.attempt,
            SttFailure.SyncStartFailed(error),
        )
        return when (decision) {
            is SttStartDecision.Retry -> {
                startSession.attempt += 1
                scheduleRetry(startSession, decision.delayMs)
                null
            }
            SttStartDecision.Fail -> {
                sttSession = null
                if (isFirst) {
                    error
                } else {
                    finishSttSession()
                    startSession.listener.onSttError(SttError.UNKNOWN)
                    null
                }
            }
        }
    }

    private fun scheduleRetry(startSession: SttSession, delayMs: Long) {
        cancelPendingRetry()
        val runnable = Runnable {
            pendingRetry = null
            if (sttSession !== startSession) return@Runnable
            startSttAttempt(startSession, isFirst = false)
        }
        pendingRetry = runnable
        retryHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRetry() {
        pendingRetry?.let { retryHandler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun finishSttSession() {
        sttSession = null
        session.reset()
        _state.value = CaptureState.Idle
    }

    /** Попросить финал (придёт в onFinal). В покое — no-op. */
    fun stopStt() {
        if (pendingRetry != null) {
            val startSession = sttSession
            cancelPendingRetry()
            finishSttSession()
            startSession?.listener?.onSttError(SttError.NO_SPEECH)
            return
        }
        runCatching { recognizer?.stopListening() }
    }

    /** Жёсткая отмена без финала. */
    fun cancelStt() {
        cancelPendingRetry()
        sttGate.invalidate()
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
        finishSttSession()
    }

    // ---- RAW ----

    companion object {
        /** Кап сырой записи: base64 минуты WAV ~3.8 МБ в запрос и историю. */
        const val MAX_RAW_SEC = 120
        const val RAW_SAMPLE_RATE = WavEncoder.SAMPLE_RATE
    }

    @Volatile
    private var rawStop = false
    private var rawThread: Thread? = null
    private var recorder: AudioRecord? = null
    private val rawPcm = ArrayList<Short>(RAW_SAMPLE_RATE * 10)
    private val rawLock = Any()

    /**
     * Начать сырую запись (WAV 16 кГц моно). Пик держим в памяти;
     * при [MAX_RAW_SEC] запись сама встаёт (отпускание холда забирает итог).
     */
    // Разрешение проверено выше через hasMicPermission(); lint его не видит.
    @android.annotation.SuppressLint("MissingPermission")
    fun startRaw(maxSec: Int = MAX_RAW_SEC): CaptureError? {
        session.start(CaptureMode.RAW)?.let { return it }
        if (!hasMicPermission()) {
            session.reset()
            return CaptureError.NO_PERMISSION
        }
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                RAW_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                session.reset()
                return CaptureError.INIT_FAILED
            }
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RAW_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                session.reset()
                return CaptureError.INIT_FAILED
            }
            synchronized(rawLock) { rawPcm.clear() }
            rawStop = false
            recorder = record
            record.startRecording()
            val startedAt = System.currentTimeMillis()
            _state.value = CaptureState.Listening(CaptureMode.RAW, startedAt)
            rawThread = thread(name = "oassist-raw-capture", isDaemon = true) {
                val buf = ShortArray(minBuf / 2)
                while (!rawStop) {
                    if (System.currentTimeMillis() - startedAt > maxSec * 1000L) break
                    val read = runCatching { record.read(buf, 0, buf.size) }.getOrElse { -1 }
                    if (read < 0) break
                    if (read > 0) {
                        synchronized(rawLock) {
                            for (i in 0 until read) rawPcm.add(buf[i])
                        }
                    }
                }
                runCatching {
                    record.stop()
                    record.release()
                }
            }
            null
        } catch (e: Exception) {
            recorder = null
            session.reset()
            CaptureError.INIT_FAILED
        }
    }

    /**
     * Остановить запись и вернуть WAV. Пусто/тишина короче секунды —
     * null (UI показывает хинт «удерживайте», а не шлёт тишину).
     */
    fun stopRaw(): WavAudio? {
        val mode = session.stop()
        rawStop = true
        runCatching { rawThread?.join(2000L) }
        rawThread = null
        recorder = null
        _state.value = CaptureState.Idle
        if (mode != CaptureMode.RAW) return null
        val pcm = synchronized(rawLock) { rawPcm.toShortArray() }
        if (pcm.size < RAW_SAMPLE_RATE) return null
        return WavAudio(WavEncoder.pcmToWav(pcm), WavEncoder.durationSec(pcm.size))
    }

    /** Бросить запись без результата. */
    fun cancelRaw() {
        session.reset()
        rawStop = true
        runCatching { rawThread?.join(1000L) }
        rawThread = null
        recorder = null
        _state.value = CaptureState.Idle
    }

    private class SttProxy(
        private val listener: SttListener,
        private val isActive: () -> Boolean,
        private val onPartialSeen: () -> Unit,
        private val onErrorIntercept: (SttError) -> Boolean,
        private val onDone: () -> Unit,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isActive()) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                onPartialSeen()
                listener.onPartial(text)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isActive()) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) listener.onFinal(text) else listener.onSttError(SttError.NO_SPEECH)
            onDone()
        }

        override fun onError(error: Int) {
            if (!isActive()) return
            val mapped = when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_SPEECH
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_SERVER -> SttError.NETWORK
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttError.BUSY
                else -> SttError.UNKNOWN
            }
            if (onErrorIntercept(mapped)) return
            listener.onSttError(mapped)
            onDone()
        }
    }
}
