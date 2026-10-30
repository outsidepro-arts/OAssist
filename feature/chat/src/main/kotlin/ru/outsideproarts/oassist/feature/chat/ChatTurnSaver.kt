package ru.outsideproarts.oassist.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Сохраняет завершённые ходы одного открытого диалога в порядке их поступления. */
internal class ChatTurnSaver(private val scope: CoroutineScope) {
    private var lastSave: Job? = null
    private var createdId: String? = null

    val isSaving: Boolean get() = lastSave?.isActive == true

    fun save(
        currentId: String?,
        create: suspend () -> String,
        append: suspend (String) -> Unit,
    ): Job {
        val previous = lastSave
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            val id = currentId ?: createdId ?: create().also { createdId = it }
            append(id)
        }
        lastSave = job
        job.start()
        return job
    }
}

/** Сохраняет очереди открытых диалогов, чтобы повторное открытие не обходило запись. */
internal class ChatTurnSavers(private val scope: CoroutineScope) {
    private val byId = mutableMapOf<String, ChatTurnSaver>()
    var current = ChatTurnSaver(scope)
        private set

    fun newChat() {
        current = ChatTurnSaver(scope)
    }

    fun openChat(id: String): ChatTurnSaver {
        current = byId.getOrPut(id) { ChatTurnSaver(scope) }
        return current
    }

    fun register(id: String, saver: ChatTurnSaver) {
        byId[id] = saver
    }
}
