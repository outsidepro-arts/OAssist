package ru.outsideproarts.oassist.feature.share

import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry

/** The storage boundary used by the SHARE session; all ordering belongs to its owner. */
internal interface ShareChatStore {
    suspend fun loadTurnEntries(id: String): List<StoredTurnEntry>
    suspend fun title(id: String): String
    suspend fun createChat(mode: AssistMode, title: String): String
    suspend fun appendTurn(id: String, turn: StoredTurn, turnId: String)
    suspend fun deleteTurn(id: String, turnId: String)
    suspend fun deleteTurnsAfter(id: String, turnId: String)
    suspend fun deleteChat(id: String)
    suspend fun renameChat(id: String, title: String)
    /** Закрепить/открепить сообщение сохранённого хода; false — ход/сообщение не найдены. */
    suspend fun setPin(id: String, turnId: String, messageIndex: Int, pinned: Boolean, note: String?): Boolean
    fun diagnosticGeneration(id: String): Long
    suspend fun bindDiagnosticOperation(id: String, operation: String, generation: Long? = null)
}

internal class RepositoryShareChatStore(private val repository: ChatRepository) : ShareChatStore {
    override suspend fun loadTurnEntries(id: String) = repository.loadTurnEntries(id)
    override suspend fun title(id: String) = repository.getChat(id)?.title.orEmpty()
    override suspend fun createChat(mode: AssistMode, title: String) = repository.createChat(mode, title)
    override suspend fun appendTurn(id: String, turn: StoredTurn, turnId: String) =
        repository.appendTurn(id, turn, turnId)

    override suspend fun deleteTurn(id: String, turnId: String) = repository.deleteTurn(id, turnId)
    override suspend fun deleteTurnsAfter(id: String, turnId: String) = repository.deleteTurnsAfter(id, turnId)
    override suspend fun deleteChat(id: String) = repository.deleteChat(id)
    override suspend fun renameChat(id: String, title: String) = repository.renameChat(id, title)
    override suspend fun setPin(id: String, turnId: String, messageIndex: Int, pinned: Boolean, note: String?): Boolean =
        repository.setPin(id, turnId, messageIndex, pinned, note)
    override fun diagnosticGeneration(id: String) = repository.diagnosticGeneration(id)
    override suspend fun bindDiagnosticOperation(id: String, operation: String, generation: Long?) {
        if (generation == null) repository.bindDiagnosticOperation(id, operation)
        else repository.bindDiagnosticOperation(id, operation, generation)
    }
}
