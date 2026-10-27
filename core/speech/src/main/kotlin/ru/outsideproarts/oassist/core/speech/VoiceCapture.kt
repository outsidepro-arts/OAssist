package ru.outsideproarts.oassist.core.speech

/**
 * Состояние и guard захвата микрофона, общие для UI (PTT/тап) и тулов агента.
 * Чистая стейт-машина (часы инжектятся), JVM-тестируема; сам захват —
 * в [VoiceListener] (Android API).
 */
enum class CaptureMode {
    /** Системный SpeechRecognizer: захват внутри, наружу — текст. */
    STT,

    /** Сырой AudioRecord → WAV: наружу — байты для модели. */
    RAW,
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Listening(val mode: CaptureMode, val startedAtMs: Long) : CaptureState
}

/** Почему не стартовали. UI маппит в строки, тулы — в текст результата. */
enum class CaptureError {
    /** Уже идёт захват (повторный старт). */
    BUSY,

    /** Нет распознавателя в системе (только STT). */
    NO_RECOGNIZER,

    /** Нет RECORD_AUDIO. */
    NO_PERMISSION,

    /** AudioRecord/SpeechRecognizer не создался. */
    INIT_FAILED,
}

/** Чем кончилось системное распознавание (для тостов/результата тула). */
enum class SttError {
    /** Долгое молчание после подсказки. */
    NO_SPEECH,

    /** Сеть/сервер распознавания недоступны. */
    NETWORK,

    /**
     * Аудиовход/движок занят (`ERROR_AUDIO`/`ERROR_CLIENT`/`ERROR_RECOGNIZER_BUSY`) —
     * типично при старте поверх ещё звучащего TTS; лечится повтором (см. [SttStartPolicy]).
     */
    BUSY,

    /** Прочие ошибки (client/busy и непокрытые коды). */
    UNKNOWN,
}

/** Колбэки системного распознавания. Все — на главном потоке. */
interface SttListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onSttError(error: SttError)
    fun onSttCancelled() = Unit
}

/** Сырой результат захвата для модели (транзитный, не сравнивается). */
class WavAudio(val wav: ByteArray, val durationSec: Int)

/**
 * Guard: один захват за раз, стоп только активного.
 * Возвращает ошибку текстом для модели/лога или null при успехе.
 */
class VoiceCaptureSession(private val clock: () -> Long = System::currentTimeMillis) {
    private var _state: CaptureState = CaptureState.Idle
    val state: CaptureState get() = _state

    fun start(mode: CaptureMode): CaptureError? {
        if (_state is CaptureState.Listening) return CaptureError.BUSY
        _state = CaptureState.Listening(mode, clock())
        return null
    }

    /** Останавливает активный захват, возвращает его режим; в покое — null. */
    fun stop(): CaptureMode? {
        val current = _state as? CaptureState.Listening ?: return null
        _state = CaptureState.Idle
        return current.mode
    }

    fun reset() {
        _state = CaptureState.Idle
    }
}

/**
 * Поколения колбэков распознавания: `SpeechRecognizer.cancel()/destroy()` доставляет
 * запоздалые `onError` уже мёртвому слушателю. Токен, выданный при старте сессии,
 * гасится при пересоздании/отмене — запоздалые колбэки не трогают ни текущую сессию,
 * ни state, ни слушателя.
 */
class SttCallbackGate {
    private var current = 0

    /** Начать новое поколение; выданный токен активен до следующего вызова. */
    fun newSession(): Int {
        current += 1
        return current
    }

    /** Погасить текущее поколение (отмена/пересоздание) — все выданные токены становятся неактивными. */
    fun invalidate() {
        current += 1
    }

    fun isActive(token: Int): Boolean = token == current
}
