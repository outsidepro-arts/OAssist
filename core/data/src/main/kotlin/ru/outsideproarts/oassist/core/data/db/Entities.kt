package ru.outsideproarts.oassist.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Диалог (строка списка чатов). [kind] — `ASSISTANT` у закреплённой записи
 * «последняя сессия ассистента» (перезаписывается каждой новой сессией).
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val mode: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Один сохранённый ход диалога. [payload] — JSON `StoredTurn` (лента UI +
 * транскрипт хода для модели); медиа — файлами на диске, в JSON — ref'ы.
 */
@Entity(tableName = "turns")
data class TurnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_id", index = true) val chatId: String,
    val seq: Int,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY CASE kind WHEN 'ASSISTANT' THEN 0 ELSE 1 END, updated_at DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun findById(id: String): ChatEntity?

    @Insert
    suspend fun insert(chat: ChatEntity)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}

@Dao
interface TurnDao {
    @Query("SELECT * FROM turns WHERE chat_id = :chatId ORDER BY seq ASC")
    fun observeForChat(chatId: String): Flow<List<TurnEntity>>

    @Query("SELECT * FROM turns WHERE chat_id = :chatId ORDER BY seq ASC")
    suspend fun listForChat(chatId: String): List<TurnEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM turns WHERE chat_id = :chatId")
    suspend fun maxSeq(chatId: String): Int

    @Insert
    suspend fun insert(turn: TurnEntity)

    @Query("DELETE FROM turns WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)

    @Query("DELETE FROM turns")
    suspend fun deleteAll()
}

/**
 * Схема v2: `chats` + `turns` (v1 `sessions`/`messages` — мёртвые, ни одной
 * записи, поэтому апгрейд деструктивный). Начиная с v2 реальные миграции
 * обязательны: в диалогах уже есть данные пользователей.
 */
@Database(
    entities = [ChatEntity::class, TurnEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class OAssistDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun turnDao(): TurnDao

    companion object {
        const val NAME = "oassist.db"
    }
}
