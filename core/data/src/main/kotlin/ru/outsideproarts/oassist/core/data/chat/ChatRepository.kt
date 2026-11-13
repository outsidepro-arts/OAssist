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
import ru.outsideproarts.oassist.core.data.db.TurnMeta
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.MessagePin
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry

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
                val diagnosticRoots = database.turnDao().listMetaForChat(id)
                    .mapNotNull { TurnCodec.diagnosticOperationId(readPayload(it)) }.toSet()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    onDiagnosticDelete(id, diagnosticRoots)
                }
                database.withTransaction {
                    database.turnDao().deleteForChat(id)
                    database.chatDao().delete(id)
                }
                diagnosticGenerations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
                deletedChatIds.add(id)
                media.deleteChat(id)
            }
        }
    }

    suspend fun getChat(id: String): ChatSummary? = database.chatDao().findById(id)?.toSummary()

    suspend fun appendTurn(chatId: String, turn: StoredTurn, turnId: String = UUID.randomUUID().toString()) {
        diagnosticOwnershipLock.withLock {
            check(database.chatDao().findById(chatId) != null) { "Chat does not exist: $chatId" }
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

    /**
     * Безвозвратное удаление одного хода: строка, медиафайлы и диагностика хода
     * стираются. Отсутствующий ход — идемпотентный no-op: повтор и гонка с другим
     * удалением не считаются ошибкой.
     */
    suspend fun deleteTurn(chatId: String, turnId: String) =
        deleteTurnsWhere(chatId) { turns -> turns.filter { it.id == turnId } }

    /**
     * Откат истории: ход [turnId] остаётся последним, все следующие стираются
     * безвозвратно (строки, медиафайлы и диагностика). Отсутствующий ход — no-op.
     */
    suspend fun deleteTurnsAfter(chatId: String, turnId: String) =
        deleteTurnsWhere(chatId) { turns ->
            val target = turns.firstOrNull { it.id == turnId }
            if (target == null) emptyList() else turns.filter { it.seq > target.seq }
        }

    /**
     * Стирает выбранные ходы. Диагностика — до транзакции (fail-closed, как в
     * [deleteChat]): сбой стирания оставляет ходы и медиа на месте, повтор допустим.
     * Медиа — только после коммита, чтобы сбой БД не оставил живой ход без файлов.
     */
    private suspend fun deleteTurnsWhere(chatId: String, select: (List<TurnMeta>) -> List<TurnMeta>) {
        diagnosticOwnershipLock.withLock {
            withContext(NonCancellable) {
                val turns = select(database.turnDao().listMetaForChat(chatId))
                if (turns.isEmpty()) return@withContext
                val roots = turns.mapNotNull { TurnCodec.diagnosticOperationId(readPayload(it)) }.toSet()
                if (roots.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        roots.forEach { onDiagnosticDiscard(it) }
                    }
                }
                val now = clock()
                database.withTransaction {
                    val chat = database.chatDao().findById(chatId)
                    database.turnDao().deleteByIds(turns.map { it.id })
                    if (chat != null) database.chatDao().update(chat.copy(updatedAt = now))
                }
                // Живое зеркало истории (оверлей ассистента) стирает ходы из памяти
                // сессии сразу после коммита: сбой очистки медиа не должен оставить
                // стёртые сообщения в контексте следующего запроса модели.
                onTurnsDeleted(chatId, turns.map { it.id }.toSet())
                turns.forEach { media.deleteTurn(chatId, it.id) }
            }
        }
    }

    /** Ходы с идентификаторами строк (ручки удаления/отката); битые payload пропускаются. */
    suspend fun loadTurnEntries(chatId: String): List<StoredTurnEntry> = diagnosticOwnershipLock.withLock {
        database.turnDao().listMetaForChat(chatId).mapNotNull { meta ->
            TurnCodec.decode(readPayload(meta), media)?.let { StoredTurnEntry(meta.id, it) }
        }
    }

    /**
     * Payload хода целиком, дочитывая кусками ([TurnDao.payloadSlice]):
     * строки бывают больше лимита CursorWindow, прямое чтение колонки на них
     * падает с `Row too big to fit into CursorWindow`.
     */
    private suspend fun readPayload(meta: TurnMeta): String {
        if (meta.payloadChars <= 0) return ""
        val builder = StringBuilder(meta.payloadChars)
        var offset = 1
        while (true) {
            val chunk = database.turnDao().payloadSlice(meta.id, offset, PAYLOAD_CHUNK_CHARS) ?: break
            if (chunk.isEmpty()) break
            builder.append(chunk)
            // substr/length SQLite считают Unicode-код-пойнты — и offset двигаем в
            // них же (chunk.length — UTF-16 юниты: на суррогатных парах чтение
            // расползлось бы и начало дублировать куски).
            offset += chunk.codePointCount(0, chunk.length)
        }
        return builder.toString()
    }

    /**
     * Закрепить/открепить одно сообщение сохранённого хода (флаг в payload хода).
     * Индекс — позиция в `modelMessages`: 0 — реплика пользователя, `k > 0` —
     * транскрипт `k - 1`. Правка payload точечная ([TurnCodec.pinPayload]) —
     * рефы отсутствующих медиафайлов не ампутируются. `false` — ход или сообщение
     * не найдены (no-op). Успешная правка уходит в [onTurnPinned] — живое зеркало
     * истории (оверлей ассистента) синхронизирует память с чужими закреплениями.
     */
    suspend fun setPin(chatId: String, turnId: String, messageIndex: Int, pinned: Boolean, note: String? = null): Boolean =
        diagnosticOwnershipLock.withLock {
            val meta = database.turnDao().listMetaForChat(chatId).firstOrNull { it.id == turnId }
                ?: return@withLock false
            val updated = TurnCodec.pinPayload(readPayload(meta), messageIndex, MessagePin(note).takeIf { pinned })
                ?: return@withLock false
            database.turnDao().updatePayload(turnId, updated)
            onTurnPinned(chatId, turnId, messageIndex, pinned, note)
            true
        }

    suspend fun loadTurns(chatId: String): List<StoredTurn> = loadTurnEntries(chatId).map { it.turn }

    /** История для модели без адресов сообщений: для стрима с инструментами
     *  закрепления используйте `loadTurnEntries(chatId).flatMap { it.modelMessages() }`. */
    suspend fun loadModelHistory(chatId: String): List<ChatMessage> =
        loadTurns(chatId).flatMap { it.modelMessages() }

    /**
     * Владелец закреплённого диалога «Ассистент»: перезапись чужим токеном
     * игнорируется (поздняя запись закрытого оверлея).
     */
    private var assistantOwner = 0L

    /** Serializes ownership changes with writes from a closing overlay. */
    suspend fun beginAssistantSession(): Long = diagnosticOwnershipLock.withLock { ++assistantOwner }

    /**
     * Полная перезапись закреплённого диалога «Ассистент» транскриптом
     * последней сессии. Пустой список — тоже перезапись (вызывать только когда
     * сессия непустая, см. вызывающего). Id ходов сохраняются: ручки удаления
     * остаются валидными между перезаписями. [turns] читается под замком
     * перезаписи — параллельное стирание ходов не затирается запоздалой записью.
     *
     * Инвариант: ходу с сохранённым id вызывающий передаёт те же байты — encode
     * пишет его медиафайлы поверх закоммиченных до транзакции, и обрыв записи
     * не должен менять содержимое уже сохранённого хода.
     */
    suspend fun replaceAssistantTurns(owner: Long? = null, turns: () -> List<StoredTurnEntry>) = diagnosticOwnershipLock.withLock {
        if (owner != null && owner != assistantOwner) return@withLock
        // Только метаданные: payload строк бывают больше лимита CursorWindow.
        val oldTurns = database.turnDao().listMetaForChat(ASSISTANT_CHAT_ID)
        val oldIds = oldTurns.map { it.id }.toSet()
        val newTurnIds = mutableListOf<String>()
        var committed = false
        var failure: Throwable? = null
        try {
            val now = clock()
            val replacements = turns().map { entry ->
                val turnId = entry.id.ifBlank { UUID.randomUUID().toString() }
                newTurnIds.add(turnId)
                TurnEntity(
                    id = turnId,
                    chatId = ASSISTANT_CHAT_ID,
                    seq = newTurnIds.size - 1,
                    payload = TurnCodec.encode(entry.turn, ASSISTANT_CHAT_ID, turnId, media),
                    createdAt = now,
                )
            }
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
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
                    replacements.forEach { database.turnDao().insert(it) }
                }
                committed = true
                // Only vanished UUIDs may be removed: the directory now also contains committed media.
                val kept = newTurnIds.toSet()
                oldTurns.filter { it.id !in kept }.forEach { media.deleteTurn(ASSISTANT_CHAT_ID, it.id) }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!committed) {
                // Стёрты только новые файлы: у переиспользованных id поверх лежат
                // байты уже закоммиченного хода, их трогать нельзя (повтор записи).
                newTurnIds.filter { it !in oldIds }.forEach { turnId ->
                    try {
                        media.deleteTurn(ASSISTANT_CHAT_ID, turnId)
                    } catch (cleanupError: Throwable) {
                        if (failure == null) throw cleanupError
                        failure.addSuppressed(cleanupError)
                    }
                }
            }
        }
    }

    /** Полное стирание (wipe/бэкап): чаты, ходы и медиа-файлы. */
    suspend fun clearAll() = diagnosticOwnershipLock.withLock {
        withContext(NonCancellable) {
            database.withTransaction {
                database.turnDao().deleteAll()
                database.chatDao().deleteAll()
            }
            media.deleteAll()
        }
    }

    var onDiagnosticDelete: (String, Set<String>) -> Unit = { _, _ -> }
    var onDiagnosticBind: (String, String) -> Unit = { _, _ -> }
    var onDiagnosticDiscard: (String) -> Unit = { }

    /** Живое зеркало истории (оверлей ассистента) синхронизирует память со стёртыми ходами. */
    var onTurnsDeleted: (String, Set<String>) -> Unit = { _, _ -> }

    /** Живое зеркало истории синхронизирует память с закреплениями из других экранов. */
    var onTurnPinned: (String, String, Int, Boolean, String?) -> Unit = { _, _, _, _, _ -> }

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

        /**
         * Размер куска дочитывания payload в символах. Ряд куска всегда должен
         * помещаться в CursorWindow (~2 МБ) с запасом на UTF-8: 4 байта на
         * символ — худший случай куска ~1 МБ.
         */
        private const val PAYLOAD_CHUNK_CHARS = 256 * 1024
    }
}
