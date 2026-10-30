package ru.outsideproarts.oassist.core.data.chat

import androidx.room.withTransaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.data.db.ChatEntity
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.data.db.TurnEntity
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.StoredTurn

/**
 * Репозиторий сохранённых диалогов: список чатов + ходы (полный контекст —
 * запрос/ответ, вызовы инструментов, медиа-файлы). Диалог либо «в списке»
 * (каждый ход пишется в БД), либо живёт в памяти UI до явного сохранения.
 */
class ChatRepository(
    private val database: OAssistDatabase,
    private val media: ChatMediaStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val diagnosticOwnershipLock = Mutex()
    private val deletedChatIds = mutableSetOf<String>()
    private val diagnosticGenerations = ConcurrentHashMap<String, AtomicLong>()

    fun observeChats(): Flow<List<ChatSummary>> =
        database.chatDao().observeAll().map { list -> list.map { it.toSummary() } }

    suspend fun createChat(mode: AssistMode, title: String, id: String = UUID.randomUUID().toString()): String {
        val now = clock()
        database.chatDao().insert(
            ChatEntity(
                id = id,
                title = title,
                kind = ChatKind.REGULAR.name,
                mode = mode.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    /** Переименование не двигает чат в списке (updated_at не трогаем). */
    suspend fun renameChat(id: String, title: String) {
        val chat = database.chatDao().findById(id) ?: return
        database.chatDao().update(chat.copy(title = title))
    }

    suspend fun deleteChat(id: String) {
        diagnosticOwnershipLock.withLock {
            withContext(NonCancellable) {
                val diagnosticRoots = database.turnDao().listForChat(id)
                    .mapNotNull { TurnCodec.diagnosticOperationId(it.payload) }.toSet()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    onDiagnosticDelete(id, diagnosticRoots)
                }
                media.deleteChat(id)
                database.withTransaction {
                    database.turnDao().deleteForChat(id)
                    database.chatDao().delete(id)
                }
                diagnosticGenerations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
                deletedChatIds.add(id)
            }
        }
    }

    suspend fun getChat(id: String): ChatSummary? = database.chatDao().findById(id)?.toSummary()

    suspend fun appendTurn(chatId: String, turn: StoredTurn) {
        diagnosticOwnershipLock.withLock {
            check(database.chatDao().findById(chatId) != null) { "Chat does not exist: $chatId" }
            val turnId = UUID.randomUUID().toString()
            var committed = false
            var failure: Throwable? = null
            try {
                val payload = TurnCodec.encode(turn, chatId, turnId, media)
                val now = clock()
                coroutineContext.ensureActive()
                withContext(NonCancellable) {
                    database.withTransaction {
                        val chat = checkNotNull(database.chatDao().findById(chatId)) { "Chat does not exist: $chatId" }
                        val seq = database.turnDao().maxSeq(chatId) + 1
                        database.turnDao().insert(TurnEntity(id = turnId, chatId = chatId, seq = seq, payload = payload, createdAt = now))
                        database.chatDao().update(chat.copy(updatedAt = now))
                    }
                    committed = true
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!committed) {
                    try {
                        media.deleteTurn(chatId, turnId)
                    } catch (cleanupError: Throwable) {
                        if (failure == null) throw cleanupError
                        failure.addSuppressed(cleanupError)
                    }
                }
            }
        }
    }

    suspend fun loadTurns(chatId: String): List<StoredTurn> =
        database.turnDao().listForChat(chatId).mapNotNull { TurnCodec.decode(it.payload, media) }

    /** История для модели: все ходы диалога (реплика пользователя + транскрипт). */
    suspend fun loadModelHistory(chatId: String): List<ChatMessage> =
        loadTurns(chatId).flatMap { it.modelMessages() }

    /**
     * Полная перезапись закреплённого диалога «Ассистент» транскриптом
     * последней сессии. Пустой список — тоже перезапись (вызывать только когда
     * сессия непустая, см. вызывающего).
     */
    suspend fun replaceAssistantTurns(turns: List<StoredTurn>) = diagnosticOwnershipLock.withLock {
        withContext(NonCancellable) {
            val now = clock()
            media.deleteChat(ASSISTANT_CHAT_ID)
            database.withTransaction {
                val existing = database.chatDao().findById(ASSISTANT_CHAT_ID)
                if (existing == null) {
                    database.chatDao().insert(
                        ChatEntity(
                            id = ASSISTANT_CHAT_ID,
                            title = "",
                            kind = ChatKind.ASSISTANT.name,
                            mode = AssistMode.ASSISTANT.name,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else {
                    database.chatDao().update(existing.copy(updatedAt = now))
                }
                database.turnDao().deleteForChat(ASSISTANT_CHAT_ID)
                turns.forEachIndexed { index, turn ->
                    val turnId = UUID.randomUUID().toString()
                    database.turnDao().insert(
                        TurnEntity(
                            id = turnId,
                            chatId = ASSISTANT_CHAT_ID,
                            seq = index,
                            payload = TurnCodec.encode(turn, ASSISTANT_CHAT_ID, turnId, media),
                            createdAt = now,
                        ),
                    )
                }
            }
        }
    }

    /** Полное стирание (wipe/бэкап): чаты, ходы и медиа-файлы. */
    suspend fun clearAll() = diagnosticOwnershipLock.withLock {
        withContext(NonCancellable) {
            media.deleteAll()
            database.withTransaction {
                database.turnDao().deleteAll()
                database.chatDao().deleteAll()
            }
        }
    }

    var onDiagnosticDelete: (String, Set<String>) -> Unit = { _, _ -> }
    var onDiagnosticBind: (String, String) -> Unit = { _, _ -> }
    var onDiagnosticDiscard: (String) -> Unit = { }

    fun diagnosticGeneration(chatId: String): Long =
        diagnosticGenerations.computeIfAbsent(chatId) { AtomicLong() }.get()

    suspend fun bindDiagnosticOperation(chatId: String, operationId: String, generation: Long = diagnosticGeneration(chatId)) {
        diagnosticOwnershipLock.withLock {
            if (chatId == ASSISTANT_CHAT_ID && generation == diagnosticGeneration(chatId) && chatId in deletedChatIds) {
                deletedChatIds.remove(chatId)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (generation != diagnosticGeneration(chatId) || chatId in deletedChatIds) onDiagnosticDiscard(operationId)
                else onDiagnosticBind(chatId, operationId)
            }
        }
    }

    private fun ChatEntity.toSummary(): ChatSummary = ChatSummary(
        id = id,
        title = title,
        kind = runCatching { ChatKind.valueOf(kind) }.getOrDefault(ChatKind.REGULAR),
        mode = runCatching { AssistMode.valueOf(mode) }.getOrDefault(AssistMode.CHAT),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        /** Фиксированный id закреплённого диалога последней сессии ассистента. */
        const val ASSISTANT_CHAT_ID = "assistant_last"
    }
}
