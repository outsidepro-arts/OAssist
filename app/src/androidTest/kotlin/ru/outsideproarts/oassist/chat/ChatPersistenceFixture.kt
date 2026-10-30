package ru.outsideproarts.oassist.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import ru.outsideproarts.oassist.core.data.chat.ChatMediaStore
import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase

internal class ChatPersistenceFixture(
    clock: () -> Long = { System.currentTimeMillis() },
    transactionExecutor: Executor? = null,
    queryExecutor: Executor? = null,
) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val mediaRoot = File(context.cacheDir, "chat-persistence-test-${UUID.randomUUID()}")
    val database = Room.inMemoryDatabaseBuilder(context, OAssistDatabase::class.java)
        .apply {
            transactionExecutor?.let { setTransactionExecutor(it) }
            queryExecutor?.let { setQueryExecutor(it) }
        }
        .build()
    val repository = ChatRepository(database, ChatMediaStore(mediaRoot), clock)

    override fun close() {
        database.close()
        mediaRoot.deleteRecursively()
    }
}
