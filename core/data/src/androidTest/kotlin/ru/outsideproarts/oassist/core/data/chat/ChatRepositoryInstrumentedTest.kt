package ru.outsideproarts.oassist.core.data.chat

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.model.*

@RunWith(AndroidJUnit4::class)
class ChatRepositoryInstrumentedTest {
    private lateinit var directory: File
    private lateinit var mediaRoot: File
    private lateinit var database: OAssistDatabase
    private lateinit var repository: ChatRepository
    @Volatile private var onQuery: ((String) -> Unit)? = null

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "ds006-007-").toFile()
        mediaRoot = File(directory, "media")
        open()
    }

    private fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.databaseBuilder(context, OAssistDatabase::class.java, File(directory, "chat.db").absolutePath)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { onQuery?.invoke(sqlQuery) }
            }, { it.run() })
            .build()
        repository = ChatRepository(database, ChatMediaStore(mediaRoot))
    }

    private fun reopen() {
        database.close()
        open()
    }

    @After fun tearDown() {
        onQuery = null
        database.close()
        directory.deleteRecursively()
    }

    private fun image(value: String) = ImagePart(
        Base64.getEncoder().encodeToString(value.toByteArray()), width = 23, height = 17,
    )

    private fun turn(value: String) = StoredTurn(
        userText = value,
        userAudio = AudioPart(Base64.getEncoder().encodeToString("audio-$value".toByteArray()), durationSec = 3),
        userImages = listOf(image(value)),
        modelUserInput = ModelUserInput(value, listOf(ContextData(ContextSource.SHARED_CONTENT, "untrusted"))),
        diagnosticOperationId = "operation-$value",
        answer = "answer-$value",
        work = listOf(WorkEvent.Thought("thinking"), WorkEvent.Tool("tool", "done", images = listOf(image("work")))),
        turn = listOf(
            ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(
                ToolCallRef("call", "tool", "{}", "signature", "wire-id"),
            ), reasoning = ReasoningBlock(
                text = "thinking", signature = "signature", replayJson = "[{\"type\":\"reasoning\",\"id\":\"r\"}]",
                anthropicReplay = AnthropicReplay("origin", "[{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"s\"},{\"type\":\"refusal\",\"refusal\":\"no\"}]"),
                geminiReplay = GeminiReplay("origin", "[{\"role\":\"model\",\"parts\":[{\"text\":\"signed\",\"thoughtSignature\":\"sig\"}]}]"),
            )),
            ChatMessage(ChatRole.TOOL, "done", toolCallId = "call", images = listOf(image("result"))),
        ),
    )

    private fun files(): Map<String, String> = if (!mediaRoot.exists()) emptyMap() else
        mediaRoot.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(mediaRoot).path to Base64.getEncoder().encodeToString(it.readBytes())
        }

    private fun abort(event: String, table: String) {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_write BEFORE $event ON $table BEGIN SELECT RAISE(ABORT, 'test abort'); END",
        )
    }

    private fun allowWrites() { database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_write") }

    @Test fun failedMediaWriteLeavesNoRowAndRetryRestoresFullTurnAfterReopen() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        mediaRoot.mkdirs()
        val blocked = File(mediaRoot, "chat").apply { writeText("not a directory") }
        assertTrue(runCatching { repository.appendTurn("chat", turn("new")) }.exceptionOrNull() is IOException)
        assertTrue(database.turnDao().listForChat("chat").isEmpty())
        assertTrue(blocked.delete())
        repository.appendTurn("chat", turn("new"))
        reopen()
        assertEquals(listOf(turn("new")), repository.loadTurns("chat"))
        assertEquals(turn("new").modelMessages(), repository.loadModelHistory("chat"))
    }

    @Test fun abortedAppendCleansNewFilesAndDoesNotTouchEarlierTurn() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("old"))
        val previous = files()
        abort("INSERT", "turns")
        assertNotNull(runCatching { repository.appendTurn("chat", turn("new")) }.exceptionOrNull())
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("old")), repository.loadTurns("chat"))
        allowWrites()
        repository.appendTurn("chat", turn("new"))
        reopen()
        assertEquals(listOf(turn("old"), turn("new")), repository.loadTurns("chat"))
    }

    @Test fun abortedReplacementKeepsOldBytesAndRetryCommitsOnlyNewReferences() = runBlocking {
        val id = ChatRepository.ASSISTANT_CHAT_ID
        repository.replaceAssistantTurns(listOf(turn("old")))
        val previous = files()
        abort("INSERT", "turns")
        assertNotNull(runCatching { repository.replaceAssistantTurns(listOf(turn("new"), turn("second"))) }.exceptionOrNull())
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("old")), repository.loadTurns(id))
        allowWrites()
        repository.replaceAssistantTurns(listOf(turn("new"), turn("second")))
        assertTrue(previous.keys.intersect(files().keys).isEmpty())
        reopen()
        assertEquals(listOf(turn("new"), turn("second")), repository.loadTurns(id))
        repository.replaceAssistantTurns(emptyList())
        reopen()
        assertTrue(repository.loadTurns(id).isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test fun failedDeleteAndClearKeepReferencesAndMediaUntilDatabaseCommit() = runBlocking {
        repository.createChat(AssistMode.CHAT, "one", "one")
        repository.createChat(AssistMode.SHARE, "two", "two")
        repository.appendTurn("one", turn("one"))
        repository.appendTurn("two", turn("two"))
        val previous = files()
        abort("DELETE", "chats")
        assertNotNull(runCatching { repository.deleteChat("one") }.exceptionOrNull())
        assertEquals(previous, files())
        assertNotNull(runCatching { repository.clearAll() }.exceptionOrNull())
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("one")), repository.loadTurns("one"))
        assertEquals(listOf(turn("two")), repository.loadTurns("two"))
        allowWrites()
        repository.deleteChat("one")
        reopen()
        assertNull(repository.getChat("one"))
        assertEquals(listOf(turn("two")), repository.loadTurns("two"))
        assertFalse(File(mediaRoot, "one").exists())
        repository.clearAll()
        reopen()
        assertNull(repository.getChat("two"))
        assertTrue(repository.loadTurns("two").isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test fun cancellationBeforeCommitCleansStagedMediaAndKeepsPreviousReferences() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("old"))
        repository.replaceAssistantTurns(listOf(turn("assistant")))
        val previous = files()
        lateinit var owner: Job
        val cancelling = ChatRepository(database, ChatMediaStore(mediaRoot)) {
            owner.cancel()
            1L
        }
        owner = launch(start = CoroutineStart.LAZY) { cancelling.appendTurn("chat", turn("cancelled")) }
        owner.start()
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals(previous, files())
        owner = launch(start = CoroutineStart.LAZY) { cancelling.replaceAssistantTurns(listOf(turn("cancelled"))) }
        owner.start()
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("old")), repository.loadTurns("chat"))
        assertEquals(listOf(turn("assistant")), repository.loadTurns(ChatRepository.ASSISTANT_CHAT_ID))
    }

    @Test fun ownerCancellationDuringCommitCompletesOneTurnBeforeQueuedDelete() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        onQuery = { sql ->
            if (sql.contains("INSERT", ignoreCase = true) && sql.contains("turns")) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "insert gate not released" }
            }
        }
        val save = launch(Dispatchers.IO) { repository.appendTurn("chat", turn("committed")) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            save.cancel()
            // The same owner lock must cover the transaction and the file lifecycle.
            val deletion = launch(start = CoroutineStart.UNDISPATCHED) { repository.deleteChat("chat") }
            assertFalse(deletion.isCompleted)
            release.countDown()
            save.join()
            deletion.join()
        } finally {
            release.countDown()
            save.cancelAndJoin()
            onQuery = null
        }
        reopen()
        assertNull(repository.getChat("chat"))
        assertTrue(repository.loadTurns("chat").isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test fun committedTurnSurvivesOwnerCancellationAndReopensExactlyOnce() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        onQuery = { sql ->
            if (sql.contains("INSERT", ignoreCase = true) && sql.contains("turns")) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val save = launch(Dispatchers.IO) { repository.appendTurn("chat", turn("committed")) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            save.cancel()
            release.countDown()
            save.join()
        } finally {
            release.countDown()
            save.cancelAndJoin()
            onQuery = null
        }
        reopen()
        assertEquals(listOf(turn("committed")), repository.loadTurns("chat"))
    }

    @Test fun hydrationAndReplacementUseTheSameStorageBoundary() = runBlocking {
        repository.replaceAssistantTurns(listOf(turn("old")))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controlledRoot = object : File(mediaRoot.absolutePath) {
            override fun getCanonicalFile(): File {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getCanonicalFile()
            }
        }
        val controlled = ChatRepository(database, ChatMediaStore(controlledRoot))
        val reading = async(Dispatchers.IO) { controlled.loadTurns(ChatRepository.ASSISTANT_CHAT_ID) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                controlled.replaceAssistantTurns(listOf(turn("new")))
            }
            assertFalse(replacement.isCompleted)
            release.countDown()
            assertEquals(listOf(turn("old")), reading.await())
            replacement.join()
        } finally {
            release.countDown()
            reading.cancelAndJoin()
        }
        reopen()
        assertEquals(listOf(turn("new")), repository.loadTurns(ChatRepository.ASSISTANT_CHAT_ID))
    }
}
