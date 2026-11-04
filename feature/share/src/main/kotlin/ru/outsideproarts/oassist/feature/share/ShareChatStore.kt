package ru.outsideproarts.oassist.feature.share

import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.StoredTurn

/** The storage boundary used by the SHARE session; all ordering belongs to its owner. */
internal interface ShareChatStore {
    suspend fun loadTurns(id: String): List<StoredTurn>
    suspend fun title(id: String): String
    suspend fun createChat(mode: AssistMode, title: String): String
    suspend fun appendTurn(id: String, turn: StoredTurn)
    suspend fun deleteChat(id: String)
    suspend fun renameChat(id: String, title: String)
    fun diagnosticGeneration(id: String): Long
    suspend fun bindDiagnosticOperation(id: String, operation: String, generation: Long? = null)
}

internal class RepositoryShareChatStore(private val repository: ChatRepository) : ShareChatStore {
    override suspend fun loadTurns(id: String) = repository.loadTurns(id)
    override suspend fun title(id: String) = repository.getChat(id)?.title.orEmpty()
    override suspend fun createChat(mode: AssistMode, title: String) = repository.createChat(mode, title)
    override suspend fun appendTurn(id: String, turn: StoredTurn) = repository.appendTurn(id, turn)
    override suspend fun deleteChat(id: String) = repository.deleteChat(id)
    override suspend fun renameChat(id: String, title: String) = repository.renameChat(id, title)
    override fun diagnosticGeneration(id: String) = repository.diagnosticGeneration(id)
    override suspend fun bindDiagnosticOperation(id: String, operation: String, generation: Long?) {
        if (generation == null) repository.bindDiagnosticOperation(id, operation)
        else repository.bindDiagnosticOperation(id, operation, generation)
    }
}
