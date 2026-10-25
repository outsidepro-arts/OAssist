package ru.outsideproarts.oassist.core.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.outsideproarts.oassist.core.model.OAssistLog
import java.util.ArrayList
import kotlin.concurrent.thread

/**
 * Захват микрофона: STT через системный SpeechRecognizer (текст наружу)
 * или RAW через AudioRecord → WAV (байты наружу для модели).
 * Один захват за раз (guard — [VoiceCaptureSession]); состояние — в [state]
 * для иконок/индикаторов UI. Все методы вызывать с главного потока.
 */
class VoiceListener(
    private val context: Context,
    private val recognitionServices: RecognitionServiceCatalog = RecognitionServiceCatalog(context),
) {

    private val session = VoiceCaptureSession()
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun isSttAvailable(recognizerComponent: String? = null): Boolean =
        recognitionServices.resolveComponent(recognizerComponent) != null ||
            SpeechRecognizer.isRecognitionAvailable(context)

    private fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---- STT ----

    private var recognizer: SpeechRecognizer? = null
    private var recognizerComponent: ComponentName? = null
    private val sttGate = SttCallbackGate()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null
    private var sttSession: SttSession? = null
    private var sttProxy: SttProxy? = null

    /**
     * Фокус на время слушания: GAIN_TRANSIENT — speech-контент (TalkBack) прерывает речь,
     * плееры встают на паузу до отпускания. Тишина нужна распознаванию, а не нам —
     * поэтому без MAY_DUCK.
     */
    private val listenFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .build()
    }

    private val listenFocus = ListenFocusWindow(
        requestFocus = {
            runCatching {
                context.getSystemService(AudioManager::class.java)?.requestAudioFocus(listenFocusRequest)
            }
        },
        abandonFocus = {
            runCatching {
                context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(listenFocusRequest)
            }
        },
    )

    /** Одна пользовательская сессия распознавания со всеми её попытками старта. */
    private class SttSession(
        val language: String,
        val listener: SttListener,
        val component: ComponentName?,
        var attempt: Int = 1,
        var hadPartial: Boolean = false,
    )

    /**
     * Начать системное распознавание. Частичные результаты — в [listener],
     * финал — тоже (там же снимать [state]). Возвращает причину отказа.
     */
    fun startStt(
        language: String,
        listener: SttListener,
        recognizerComponent: String? = null,
    ): CaptureError? {
        session.start(CaptureMode.STT)?.let { return it }
        if (!hasMicPermission()) {
            session.reset()
            return CaptureError.NO_PERMISSION
        }
        val component = recognitionServices.resolveComponent(recognizerComponent)
        if (component == null && !SpeechRecognizer.isRecognitionAvailable(context)) {
            session.reset()
            return CaptureError.NO_RECOGNIZER
        }
        val startSession = SttSession(language, listener, component)
        sttSession = startSession
        // Фокус берём до старта захвата и держим через все retry-попытки одной сессии.
        listenFocus.open()
        val error = startSttAttempt(startSession, isFirst = true)
        if (error != null) {
            finishSttSession()
            return error
        }
        return null
    }

    /**
     * Одна попытка старта движка. Повторяемые отказы уходят в отложенный повтор;
     * финальные — наружу через SttListener.onSttError.
     */
    private fun startSttAttempt(startSession: SttSession, isFirst: Boolean): CaptureError? {
        startSession.hadPartial = false
        val token = sttGate.newSession()
        return try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, startSession.language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            val engine = recognizerFor(startSession.component)
            engine.setRecognitionListener(SttProxy(startSession, token).also { sttProxy = it })
            engine.startListening(intent)
            _state.value = CaptureState.Listening(CaptureMode.STT, System.currentTimeMillis())
            null
        } catch (e: Exception) {
            releaseRecognizer()
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
        sttGate.invalidate()
        detachSttProxy()
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
        sttGate.invalidate()
        detachSttProxy()
        cancelPendingRetry()
        sttSession = null
        session.reset()
        _state.value = CaptureState.Idle
        listenFocus.close()
    }

    private fun detachSttProxy() {
        sttProxy?.detach()
        sttProxy = null
    }

    /** Попросить финал (придёт в onFinal). В покое — no-op. */
    fun stopStt() {
        if (sttSession == null) return
        if (pendingRetry != null) {
            val startSession = sttSession
            cancelPendingRetry()
            finishSttSession()
            startSession?.listener?.onSttError(SttError.NO_SPEECH)
            return
        }
        runCatching { recognizer?.stopListening() }
    }

    /** Жёсткая отмена без финала и без общих callback со следующим захватом. */
    fun cancelStt() {
        cancelPendingRetry()
        sttGate.invalidate()
        if (sttSession != null) {
            runCatching { recognizer?.cancel() }
            releaseRecognizer()
        }
        finishSttSession()
    }

    /** Освободить распознаватель, когда владелец VoiceListener больше не нужен. */
    fun close() {
        cancelRaw()
        cancelStt()
        releaseRecognizer()
    }

    private fun recognizerFor(component: ComponentName?): SpeechRecognizer {
        // В SDK нет константы этого ключа; Android читает его при подключении default.
        val identity = component ?: runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                ?.let(ComponentName::unflattenFromString)
        }.getOrNull()
        recognizer?.let { if (identity != null && recognizerComponent == identity) return it }
        releaseRecognizer()
        return SpeechRecognizer.createSpeechRecognizer(context, component).also {
            recognizer = it
            recognizerComponent = identity
        }
    }

    private fun releaseRecognizer() {
        val previous = recognizer
        recognizer = null
        recognizerComponent = null
        runCatching { previous?.destroy() }
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

    private inner class SttProxy(
        private var target: SttSession?,
        private val token: Int,
    ) : RecognitionListener {
        fun detach() {
            target = null
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!sttGate.isActive(token)) return
            val capture = target ?: return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                capture.hadPartial = true
                capture.listener.onPartial(text)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!sttGate.isActive(token)) return
            val capture = target ?: return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            finishSttSession()
            if (text.isNotEmpty()) capture.listener.onFinal(text)
            else capture.listener.onSttError(SttError.NO_SPEECH)
        }

        override fun onError(error: Int) {
            if (!sttGate.isActive(token)) return
            val capture = target ?: return
            OAssistLog.info("OAssistStt", "Recognition error code=$error attempt=${capture.attempt}")
            if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
            ) releaseRecognizer()
            val mapped = when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_SPEECH
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_SERVER -> SttError.NETWORK
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SttError.BUSY
                else -> SttError.UNKNOWN
            }
            if (handleAsyncError(capture, mapped)) return
            finishSttSession()
            capture.listener.onSttError(mapped)
        }
    }
}
