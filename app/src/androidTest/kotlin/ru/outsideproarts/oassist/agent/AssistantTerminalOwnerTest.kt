package ru.outsideproarts.oassist.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.chat.ChatMediaStore
import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry

@RunWith(AndroidJUnit4::class)
class AssistantTerminalOwnerTest {
    @Test fun lateOldOwnerCannotReplaceNewSessionAndFailedSaveCanRetryWithoutDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "assistant-owner-${UUID.randomUUID()}.db"
        val media = File(context.cacheDir, name)
        fun open() = Room.databaseBuilder(context, OAssistDatabase::class.java, name).build()
        var db = open()
        try {
            val repo = ChatRepository(db, ChatMediaStore(media))
            val old = repo.beginAssistantSession()
            val lateResult = CompletableDeferred<Unit>()
            val oldSave = launch {
                lateResult.await()
                repo.replaceAssistantTurns(old) { listOf(StoredTurnEntry("old-owner", StoredTurn(userText = "old owner"))) }
            }
            val current = repo.beginAssistantSession()
            val first = StoredTurn(userText = "current owner")
            repo.replaceAssistantTurns(current) { listOf(StoredTurnEntry("first", first)) }
            lateResult.complete(Unit)
            oldSave.join()
            assertEquals(listOf(first), repo.loadTurns(ChatRepository.ASSISTANT_CHAT_ID))
            // Fail the actual SQLite insert after replacement has started; rollback must keep the old row.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER terminal_fail BEFORE INSERT ON turns BEGIN SELECT RAISE(ABORT, 'terminal save fixture'); END")
            val second = StoredTurn(userText = "second terminal")
            assertNotNull(runCatching {
                repo.replaceAssistantTurns(current) {
                    listOf(StoredTurnEntry("first", first), StoredTurnEntry("second", second))
                }
            }.exceptionOrNull())
            assertEquals(listOf(first), repo.loadTurns(ChatRepository.ASSISTANT_CHAT_ID))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER terminal_fail")
            repo.replaceAssistantTurns(current) {
                listOf(StoredTurnEntry("first", first), StoredTurnEntry("second", second))
            }
            db.close()
            db = open()
            assertEquals(listOf(first, second), ChatRepository(db, ChatMediaStore(media)).loadTurns(ChatRepository.ASSISTANT_CHAT_ID))
        } finally {
            db.close()
            context.deleteDatabase(name)
            media.deleteRecursively()
        }
    }
}
