package ru.outsideproarts.oassist.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.a11y.Announcer
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.core.model.SpeechOutputChannel
import ru.outsideproarts.oassist.core.model.TtsConfig
import java.util.Locale

/** Голос движка для выбора в UI. Чистые данные, без Android-зависимостей. */
data class VoiceInfo(
    val name: String,
    val localeTag: String,
    /** Пакет движка-владельца (имена голосов уникальны только внутри движка). */
    val enginePackage: String? = null,
    val quality: Int = 0,
    val requiresNetwork: Boolean = false,
)

/** Установленный в системе синтезатор речи (`TextToSpeech.getEngines`). */
data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean = false,
)

/** Distinct-сортированные теги локалей голосов — дропдаун языков в UI. */
fun voiceLanguageTags(voices: List<VoiceInfo>): List<String> =
    voices.map { it.localeTag }.distinct().sorted()

/**
 * Озвучка ответов агента. Канал — [SpeechOutputChannel] из `core/model`
 * (единый с настройками и документацией; локального дубликата больше нет).
 *
 * Контракт каналов:
 * - `OFF` — молча;
 * - `SYSTEM_TTS` — Android TextToSpeech, фразы в `QUEUE_ADD` (очередь движка);
 * - `SCREEN_READER` — без звука: каждое завершённое предложение — в [announcer]
 *   (скрытый live-region узел, читает TalkBack);
 * - `ONLINE_TTS` — отложен: фраза пропускается с логом, очередь не рвётся.
 *
 * Все методы неблокирующие (`speak` — один вызов TTS / `tryEmit` анонсера),
 * `runBlocking` здесь запрещён. Смена канала через сеттер [channel] очередь не
 * теряет: уже поставленные в TTS фразы договариваются, новые идут в новый канал.
 */
class SpeechOutput(
    private val context: Context,
    private val announcer: Announcer? = null,
) {

    /**
     * Смена канала без потери очереди (см. контракт выше): уже поставленные
     * в TTS фразы договариваются, новые идут в новый канал.
     */
    var channel: SpeechOutputChannel = SpeechOutputChannel.SYSTEM_TTS
        set(value) {
            if (field == value) return
            stop()
            field = value
            if (value == SpeechOutputChannel.SYSTEM_TTS) ensureTts()
        }

    var locale: Locale = Locale.forLanguageTag("ru-RU")
    /** Скорость речи (0.5–2.0). Применяется к движку сразу и при его инициализации. */
    var rate: Float = 1f
    /** Тон речи (0.5–2.0). Применяется к движку сразу и при его инициализации. */
    var pitch: Float = 1f
    /** Громкость фраз (0–1, KEY_PARAM_VOLUME на фразу; часть движков игнорирует). */
    var volume: Float = 1f
    /** Имя голоса в скоупе [enginePackage], null — голос по умолчанию. */
    var voiceName: String? = null
    /**
     * Пакет синтезатора (`getEngines`), null — системный по умолчанию.
     * Смена пересоздаёт инстанс (setEngineByPackageName deprecated) —
     * редкая операция (сохранение настроек/смена режима), задержка допустима.
     */
    var enginePackage: String? = null
        private set

    private var tts: TextToSpeech? = null
    private var boundEngine: String? = null
    private val ready = MutableStateFlow(false)

    private val pendingLock = Any()
    private val pendingUtterances = mutableSetOf<String>()
    private val _speaking = MutableStateFlow(false)

    /** true, пока очередь TTS непуста; false = всё договорено (или канал не SYSTEM_TTS). */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> = _speaking

    private val speechAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Ducking чужого звука на время озвучки: MAY_DUCK и БЕЗ willPauseWhenDucked — плееры
     * только убавляются (стандартный auto-duck), не встают на паузу.
     * setForceDucking здесь не работает: система чествует его только для
     * USAGE_ASSISTANCE_ACCESSIBILITY из accessibility-сервиса.
     */
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAudioAttributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
    }

    private val focusWindow = TtsFocusWindow(
        requestFocus = {
            runCatching {
                context.getSystemService(AudioManager::class.java)?.requestAudioFocus(focusRequest)
            }
        },
        abandonFocus = {
            runCatching {
                context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(focusRequest)
            }
        },
    )

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            utteranceFinished(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceFinished(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceFinished(utteranceId)
        }
    }

    private fun utteranceFinished(utteranceId: String?) {
        if (utteranceId == null) return
        synchronized(pendingLock) {
            if (pendingUtterances.remove(utteranceId)) {
                focusWindow.onUtteranceFinished()
                if (pendingUtterances.isEmpty()) _speaking.value = false
            }
        }
    }

    private fun utteranceStarted(utteranceId: String) {
        synchronized(pendingLock) {
            pendingUtterances.add(utteranceId)
            _speaking.value = true
            focusWindow.onUtteranceStarted()
        }
    }

    private fun resetUtterances() {
        synchronized(pendingLock) {
            pendingUtterances.clear()
            _speaking.value = false
            focusWindow.reset()
        }
    }

    fun init() {
        ensureTts()
    }

    /**
     * Применить конфиг озвучки режима целиком: канал + движок + тембр + голос.
     * Уже поставленные в TTS фразы договариваются (см. сеттер [channel]).
     */
    fun applyConfig(config: TtsConfig) {
        val wantedEngine = config.enginePackage?.takeIf { it.isNotBlank() && isKnownEngine(it) }
        if (wantedEngine != enginePackage) {
            enginePackage = wantedEngine
            restartTts()
        }
        channel = config.channel
        rate = config.rate.coerceIn(TtsConfig.MIN_RATE, TtsConfig.MAX_RATE)
        pitch = config.pitch.coerceIn(TtsConfig.MIN_PITCH, TtsConfig.MAX_PITCH)
        volume = config.volume.coerceIn(TtsConfig.MIN_VOLUME, TtsConfig.MAX_VOLUME)
        voiceName = config.voiceName?.takeIf { it.isNotBlank() }
        applyEngineParams()
    }

    /** Все установленные синтезаторы: дефолт первым, дальше по имени. */
    suspend fun availableEngines(timeoutMs: Long = 5_000L): List<TtsEngineInfo> {
        val live = tts
        if (live != null && ready.value) return readEngines(live)
        ensureTts()
        val ok = withTimeoutOrNull(timeoutMs) { ready.first { it } } ?: false
        if (ok) {
            tts?.let { return readEngines(it) }
        }
        // Живого инстанса нет (канал не TTS) — временный на дефолтном движке.
        return withTempEngine(null, timeoutMs) { readEngines(it) } ?: emptyList()
    }

    private fun readEngines(engine: TextToSpeech): List<TtsEngineInfo> =
        runCatching {
            val defaultPkg = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
                )
            }.getOrNull()
            engine.engines.orEmpty()
                .map { TtsEngineInfo(it.name, it.label, it.name == defaultPkg) }
                .sortedWith(compareByDescending<TtsEngineInfo> { it.isDefault }.thenBy { it.label })
        }.getOrElse { emptyList() }

    private fun isKnownEngine(pkg: String): Boolean =
        // Пакета нет среди TTS-сервисов — не блокируем выбор (инициализация
        // упадёт с таймаутом штатно), getOrElse — тоже «не блокировать».
        runCatching {
            val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            context.packageManager.queryIntentServices(intent, 0)
                .any { it.serviceInfo.packageName == pkg }
        }.getOrElse { true }

    @Synchronized
    private fun restartTts() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready.tryEmit(false)
        resetUtterances()
        ensureTts()
    }

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            applyEngineParams()
            ready.tryEmit(true)
        } else {
            OAssistLog.info(TAG, "TTS init failed")
        }
    }

    @Synchronized
    private fun ensureTts() {
        if (channel != SpeechOutputChannel.SYSTEM_TTS || tts != null) return
        val pkg = enginePackage
        boundEngine = pkg
        tts = if (pkg == null) {
            TextToSpeech(context, initListener)
        } else {
            TextToSpeech(context, initListener, pkg)
        }
        tts?.setOnUtteranceProgressListener(utteranceListener)
    }

    /** Скорость/тон/голос движка; безопасно вызывать до готовности (движка может не быть). */
    private fun applyEngineParams() {
        val engine = tts ?: return
        engine.setAudioAttributes(speechAudioAttributes)
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        val wanted = voiceName
        if (wanted != null) {
            val voice = runCatching { engine.voices }.getOrNull()?.find { it.name == wanted }
            if (voice != null) {
                engine.voice = voice
                return
            }
        }
        engine.language = locale
    }

    /**
     * Голоса движка для выбора в UI. `null` — привязанный движок.
     * Чужой движок читается через временный инстанс (shutdown в finally).
     * Пусто — движок снесли/недоступен (выбор скрыть, не падать).
     * Вызывать с Looper-потока (UI): конструктор TextToSpeech его требует.
     */
    suspend fun availableVoices(enginePackage: String?, timeoutMs: Long = 5_000L): List<VoiceInfo> {
        // Неизвестный пакет (движок снесли) — пусто, а не молча дефолт.
        val target = enginePackage?.takeIf { it.isNotBlank() }
        if (target != null && !isKnownEngine(target)) return emptyList()
        val live = tts
        if (target == boundEngine && live != null && ready.value) {
            return readVoices(live, target)
        }
        // Чужой движок (или живой недоступен) — временный инстанс.
        return withTempEngine(target, timeoutMs) { readVoices(it, target) } ?: emptyList()
    }

    /** Временный инстанс на пакете (null — дефолт): выполнить и закрыть. */
    private suspend fun <T> withTempEngine(
        pkg: String?,
        timeoutMs: Long,
        block: (TextToSpeech) -> T,
    ): T? {
        val done = CompletableDeferred<Boolean>()
        val tmp = try {
            if (pkg == null) {
                TextToSpeech(context) { status -> done.complete(status == TextToSpeech.SUCCESS) }
            } else {
                TextToSpeech(context, { status -> done.complete(status == TextToSpeech.SUCCESS) }, pkg)
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
            if (!ok) null else block(tmp)
        } finally {
            runCatching { tmp.shutdown() }
        }
    }

    private fun readVoices(engine: TextToSpeech?, pkg: String?): List<VoiceInfo> =
        runCatching {
            engine?.voices.orEmpty().map {
                VoiceInfo(
                    name = it.name,
                    localeTag = it.locale.toLanguageTag(),
                    enginePackage = pkg,
                    quality = it.quality,
                    requiresNetwork = it.isNetworkConnectionRequired,
                )
            }.sortedWith(compareBy({ it.localeTag }, { it.name }))
        }.getOrElse { emptyList() }

    /** Озвучить одно завершённое предложение. */
    fun speak(sentence: String) {
        val text = sentence.trim()
        if (text.isEmpty()) return
        when (channel) {
            SpeechOutputChannel.OFF -> {}
            SpeechOutputChannel.SYSTEM_TTS -> {
                ensureTts()
                val engine = tts
                if (engine != null && ready.value) {
                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                    }
                    val utteranceId = "oassist-${System.nanoTime()}"
                    utteranceStarted(utteranceId)
                    engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                }
            }
            SpeechOutputChannel.SCREEN_READER -> {
                announcer?.announce(text)
            }
            SpeechOutputChannel.ONLINE_TTS -> {
                // Отложен (M4.x): пропускаем фразу, не рвём очередь стрима.
                OAssistLog.info(TAG, "ONLINE_TTS отложен, фраза пропущена")
            }
        }
    }

    /** Разбить текст на предложения и озвучить каждое. */
    fun speakSentences(text: String) {
        SentenceSplitter.splitSentences(text).forEach { speak(it) }
    }

    /**
     * Стоп / barge-in: гасит TTS и сбрасывает очередь движка.
     * Уже сказанное остаётся сказанным; анонсер событий назад не забирает.
     */
    fun stop() {
        tts?.stop()
        resetUtterances()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.tryEmit(false)
        resetUtterances()
    }

    companion object {
        private const val TAG = "SpeechOutput"
    }
}
