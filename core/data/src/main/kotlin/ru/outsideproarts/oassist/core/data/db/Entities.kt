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

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id", index = true) val sessionId: String,
    val role: String,
    val content: String,
    val seq: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun findById(id: String): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY seq ASC")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY seq ASC")
    suspend fun listForSession(sessionId: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OAssistDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "oassist.db"
    }
}
