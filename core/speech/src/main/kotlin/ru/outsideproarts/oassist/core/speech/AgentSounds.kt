package ru.outsideproarts.oassist.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.model.OAssistLog
import kotlin.coroutines.resume

/**
 * Плеер событийных звуков агента.
 *
 * Правила:
 * - 9 звуков — [SoundPool] (`USAGE_ASSISTANCE_SONIFICATION`): короткие UI-звуки,
 *   играют параллельно, fire-and-forget. Фокус не запрашиваем, беззвучный
 *   режим/DND уважаются самим usage. Независим от [SpeechOutput.stop]:
 *   стоп/бардж-ин озвучки звуки не гасит.
 * - `overlay_start` — [MediaPlayer] через [playOverlayStartAndAwait]: оверлей
 *   должен прозвучать целиком ДО старта STT, поэтому вызывающий ждёт конца
 *   (со страховочным таймаутом, чтобы битый файл не блокировал микрофон).
 */
class AgentSounds(context: Context) {

    private val appContext = context.applicationContext
    private val initMutex = Mutex()
    private var pool: SoundPool? = null
    private val sampleIds = mutableMapOf<AgentSound, Int>()
    private val loaded = mutableSetOf<AgentSound>()

    /** Предзагрузка сэмплов; вызывать один раз (AppGraph). Повторный вызов — no-op. */
    suspend fun init() {
        initMutex.withLock {
            if (pool != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val created = SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(attrs)
                .build()
            created.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    synchronized(loaded) {
                        sampleIds.entries.firstOrNull { it.value == sampleId }
                            ?.let { loaded.add(it.key) }
                    }
                }
            }
            // overlay_start идёт через MediaPlayer (нужен конец для гейта STT) —
            // в пул его не кладём.
            for (sound in AgentSound.entries) {
                if (sound == AgentSound.OVERLAY_START) continue
                val resId = resIdOf(sound)
                if (resId != 0) {
                    runCatching { created.load(appContext, resId, 1) }
                        .onSuccess { sampleIds[sound] = it }
                }
            }
            pool = created
        }
    }

    /** Проиграть звук, не блокируя вызывающего. До конца init — молча пропуск. */
    fun play(sound: AgentSound) {
        if (sound == AgentSound.OVERLAY_START) return
        val p = pool ?: return
        val id = synchronized(loaded) { sampleIds[sound]?.takeIf { sound in loaded } }
        if (id == null) return
        runCatching { p.play(id, VOLUME, VOLUME, 1, 0, 1f) }
    }

    /**
     * Проиграть `overlay_start` и дождаться конца (для гейта «сначала звук,
     * затем STT»). Вызывать с Main-потока (колбэк MediaPlayer требует Looper).
     * Битый файл/отсутствие декодера — тихий пропуск по таймауту [timeoutMs].
     */
    suspend fun playOverlayStartAndAwait(timeoutMs: Long = OVERLAY_START_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val player = runCatching {
                    MediaPlayer.create(appContext, resIdOf(AgentSound.OVERLAY_START))
                }.getOrNull()
                if (player == null) {
                    OAssistLog.info(TAG, "overlay_start: MediaPlayer.create вернул null")
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { runCatching { player.release() } }
                player.setOnCompletionListener {
                    runCatching { player.release() }
                    runCatching { cont.resume(Unit) }
                }
                runCatching { player.start() }.onFailure {
                    runCatching { player.release() }
                    runCatching { cont.resume(Unit) }
                }
            }
        }
    }

    fun shutdown() {
        runCatching { pool?.release() }
        pool = null
        synchronized(loaded) {
            sampleIds.clear()
            loaded.clear()
        }
    }

    private fun resIdOf(sound: AgentSound): Int = when (sound) {
        AgentSound.ANSWER_READY -> R.raw.answer_ready
        AgentSound.ERROR -> R.raw.error
        AgentSound.MSG_SEND -> R.raw.msg_send
        AgentSound.OVERLAY_START -> R.raw.overlay_start
        AgentSound.OVERLAY_STOP -> R.raw.overlay_stop
        AgentSound.PERMISSION_DENIED -> R.raw.permission_denied
        AgentSound.PERMISSION_REQUEST -> R.raw.permission_request
        AgentSound.THINKING -> R.raw.thinking
        AgentSound.TOOL_CALL_READ -> R.raw.tool_call_read
        AgentSound.TOOL_CALL_WRITE -> R.raw.tool_call_write
    }

    companion object {
        private const val TAG = "AgentSounds"
        private const val MAX_STREAMS = 4
        private const val VOLUME = 1f
        const val OVERLAY_START_TIMEOUT_MS = 1_500L
    }
}
