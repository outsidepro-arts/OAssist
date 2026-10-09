package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Озвучка ответов через экранный чтец — аналог TeamTalk `TTSENGINE_QTANNOUNCEMENT`
 * (`QAccessibleAnnouncementEvent`, а не синтез речи): звука не издаём, каждое
 * предложение постим как `TYPE_ANNOUNCEMENT` (`View.announceForAccessibility`),
 * зачитывает сам TalkBack своим голосом и темпом.
 *
 * Почему не live-region-узел: скрытый `contentDescription`-узел теряет фразы
 * (перезапись до рекомпозиции, одинаковый текст не даёт события), а из
 * нефокусируемого оверлей-окна его события не доходят вовсе. Прямой анонс
 * встаёт в очередь скринридера и не требует фокуса на узле.
 *
 * Правила: предложения — строго по очереди (не прерывать, не склеивать);
 * пауза между постами держит события раздельными; `stop` гасит очередь
 * (barge-in/Стоп/новый вопрос); `announceAssertive` — поверх очереди.
 */
class ScreenReaderSpeaker(
    scope: CoroutineScope? = null,
    private var output: (String) -> Unit = {},
    private val delayer: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val ownsScope = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutex = Mutex()
    private val pending = ArrayDeque<String>()
    private var worker: Job? = null
    private val _speaking = MutableStateFlow(false)

    /** true, пока очередь не пуста (для гейта автопродолжения диалога). */
    val speaking: StateFlow<Boolean> = _speaking

    fun setOutput(output: (String) -> Unit) {
        this.output = output
    }

    /** Поставить фразу в очередь (пустые отбрасываются). */
    fun announce(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            mutex.withLock { pending.addLast(clean) }
            ensureWorker()
        }
    }

    /**
     * Срочное объявление (ошибка/подтверждение): очередь сбрасывается,
     * фраза уходит немедленно.
     */
    fun announceAssertive(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            mutex.withLock {
                pending.clear()
                worker?.cancel()
                worker = null
                _speaking.value = false
            }
            output(clean)
        }
    }

    /** Стоп / barge-in: очередь сбрасывается, недоговорённое не звучит. */
    fun stop() {
        scope.launch {
            mutex.withLock {
                pending.clear()
                worker?.cancel()
                worker = null
                _speaking.value = false
            }
        }
    }

    fun shutdown() {
        scope.launch {
            mutex.withLock {
                pending.clear()
                worker?.cancel()
                worker = null
                _speaking.value = false
            }
        }
        if (ownsScope) scope.cancel()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            _speaking.value = true
            try {
                while (true) {
                    val next = mutex.withLock {
                        if (pending.isEmpty()) null else pending.removeFirst()
                    } ?: break
                    output(next)
                    delayer(INTER_ANNOUNCE_DELAY_MS)
                }
            } finally {
                mutex.withLock {
                    if (pending.isEmpty()) {
                        worker = null
                        _speaking.value = false
                    } else {
                        worker = null
                        ensureWorker()
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Пауза между постами: события `TYPE_ANNOUNCEMENT` склеенные в одну
         * миллисекунду TalkBack может слить в одно; фиксированных 350 мс
         * достаточно, чтобы держать их раздельными, без привязки к длине
         * (очередь речи ведёт сам скринридер).
         */
        const val INTER_ANNOUNCE_DELAY_MS = 350L
    }
}
