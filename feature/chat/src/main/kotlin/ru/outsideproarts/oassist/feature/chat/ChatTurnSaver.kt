package ru.outsideproarts.oassist.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Сохраняет завершённые ходы одного открытого диалога в порядке их поступления. */
internal class ChatTurnSaver(private val scope: CoroutineScope) {
    private var lastSave: Job? = null
    private var createdId: String? = null
    private val pending = ArrayDeque<suspend () -> Unit>()
    private var discarded = false
    var failure by mutableStateOf<Exception?>(null)
        private set
    var hasSaveRequest = false
        private set

    val isSaving: Boolean get() = lastSave?.isActive == true

    fun save(
        currentId: String?,
        create: suspend () -> String,
        append: suspend (String) -> Unit,
    ): Job {
        if (discarded) return Job().apply { complete() }
        hasSaveRequest = true
        pending.addLast {
            val id = currentId ?: createdId ?: create().also { createdId = it }
            append(id)
        }
        return if (failure == null) drain() else checkNotNull(lastSave)
    }

    /** A failed head stays in memory; later turns may only follow its explicit retry. */
    fun retry(): Job {
        failure = null
        return drain()
    }

    suspend fun awaitSaved(): Boolean {
        lastSave?.join()
        return !discarded && failure == null && pending.isEmpty()
    }

    fun discard() {
        discarded = true
        lastSave?.cancel()
        pending.clear()
        failure = null
    }

    private fun drain(): Job {
        lastSave?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                try {
                    pending.first().invoke()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failure = error
                    return@launch
                }
                // discard may have removed this turn while its transaction was completing.
                if (pending.isNotEmpty()) pending.removeFirst()
            }
        }
        lastSave = job
        job.start()
        return job
    }
}

/** Сохраняет очереди открытых диалогов, чтобы повторное открытие не обходило запись. */
internal class ChatTurnSavers(private val scope: CoroutineScope) {
    private val byId = mutableMapOf<String, ChatTurnSaver>()
    private val all = mutableStateListOf<ChatTurnSaver>()
    private fun create() = ChatTurnSaver(scope).also { all.add(it) }
    var current = create()
        private set
    val hasFailures: Boolean get() = all.any { it.failure != null }

    fun newChat() {
        current = create()
    }

    fun openChat(id: String): ChatTurnSaver {
        current = forChat(id)
        return current
    }

    fun register(id: String, saver: ChatTurnSaver) {
        byId[id] = saver
    }

    fun forChat(id: String): ChatTurnSaver = byId.getOrPut(id) { create() }

    fun retryFailed() {
        all.filter { it.failure != null }.forEach { it.retry() }
    }

    fun discard(id: String): Boolean {
        val saver = byId.remove(id) ?: return false
        saver.discard()
        return saver === current
    }
}
