package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
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

    private data class Announcement(
        val text: String,
        val generation: Long,
        val receipt: kotlinx.coroutines.CompletableDeferred<Unit>,
    )

    private val mutex = Mutex()
    private val admissionLock = Any()
    private val receipts = SpeechCompletionTracker()
    private val pending = ArrayDeque<Announcement>()
    private var generation = 0L
    private var accepting = true
    @Volatile
    private var worker: Job? = null
    private var workerGeneration = -1L
    private val _speaking = MutableStateFlow(false)

    /** true, пока очередь не пуста (для гейта автопродолжения диалога). */
    val speaking: StateFlow<Boolean> = _speaking

    fun setOutput(output: (String) -> Unit) {
        synchronized(admissionLock) { this.output = output }
    }

    /** Поставить фразу в очередь (пустые отбрасываются). */
    fun announce(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val item = synchronized(admissionLock) {
            if (!accepting || scope.coroutineContext[Job]?.isActive == false) return false
            Announcement(clean, generation, receipts.register()).also { _speaking.value = true }
        }
        val admitted = java.util.concurrent.atomic.AtomicBoolean(false)
        scope.launch {
            mutex.withLock {
                val current = synchronized(admissionLock) { accepting && item.generation == generation }
                if (current) {
                    pending.addLast(item)
                    admitted.set(true)
                    ensureWorker()
                }
            }
        }.invokeOnCompletion {
            if (!admitted.get()) {
                receipts.complete(item.receipt)
                synchronized(admissionLock) {
                    if (item.generation == generation && worker?.isActive != true && receipts.snapshot().isEmpty()) {
                        _speaking.value = false
                    }
                }
            }
        }
        return true
    }

    /** Срочное объявление прерывает ожидающую очередь. */
    fun announceAssertive(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stop()
        announce(clean)
    }

    /** Ожидание только тех объявлений, которые уже приняты на момент вызова. */
    suspend fun awaitIdle() {
        pendingReceipts().awaitAll()
    }

    internal fun pendingReceipts(): List<Deferred<Unit>> = receipts.snapshot()

    /** Стоп / barge-in: очередь сбрасывается, недоговорённое не звучит. */
    fun stop() {
        val cutoff = synchronized(admissionLock) {
            generation += 1
            receipts.completeAll()
            _speaking.value = false
            generation
        }
        scope.launch {
            mutex.withLock {
                pending.removeAll { it.generation < cutoff }
                if (workerGeneration < cutoff) {
                    worker?.cancel()
                    worker = null
                    workerGeneration = -1L
                }
                if (pending.isNotEmpty()) ensureWorker()
            }
        }
    }

    fun shutdown() {
        synchronized(admissionLock) { accepting = false }
        stop()
        if (ownsScope) scope.cancel()
    }

    /** Caller holds mutex; old workers may finish after their replacement starts. */
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        val nextGeneration = pending.firstOrNull()?.generation ?: return
        workerGeneration = nextGeneration
        val nextWorker = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                while (true) {
                    val next = mutex.withLock {
                        if (pending.firstOrNull()?.generation == nextGeneration) pending.removeFirst() else null
                    } ?: break
                    try {
                        synchronized(admissionLock) {
                            if (accepting && next.generation == generation) output(next.text)
                        }
                    } finally {
                        receipts.complete(next.receipt)
                    }
                    delayer(INTER_ANNOUNCE_DELAY_MS)
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (worker === self) {
                            worker = null
                            workerGeneration = -1L
                            if (pending.isNotEmpty()) {
                                ensureWorker()
                            } else {
                                synchronized(admissionLock) {
                                    if (receipts.snapshot().isEmpty()) _speaking.value = false
                                }
                            }
                        }
                    }
                }
            }
        }
        worker = nextWorker
        nextWorker.invokeOnCompletion {
            if (scope.coroutineContext[Job]?.isActive == false) {
                synchronized(admissionLock) {
                    accepting = false
                    receipts.completeAll()
                    _speaking.value = false
                }
            }
        }
        nextWorker.start()
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
