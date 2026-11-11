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

    /** Записи с уникальными id: перезапись сохраняет id, повторный вызов — новые ходы. */
    private fun entries(vararg turns: StoredTurn): List<StoredTurnEntry> =
        turns.map { StoredTurnEntry(java.util.UUID.randomUUID().toString(), it) }

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
        repository.replaceAssistantTurns { entries(turn("old")) }
        val previous = files()
        abort("INSERT", "turns")
        assertNotNull(runCatching { repository.replaceAssistantTurns { entries(turn("new"), turn("second")) } }.exceptionOrNull())
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("old")), repository.loadTurns(id))
        allowWrites()
        repository.replaceAssistantTurns { entries(turn("new"), turn("second")) }
        assertTrue(previous.keys.intersect(files().keys).isEmpty())
        reopen()
        assertEquals(listOf(turn("new"), turn("second")), repository.loadTurns(id))
        repository.replaceAssistantTurns { emptyList() }
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
        repository.replaceAssistantTurns { entries(turn("assistant")) }
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
        owner = launch(start = CoroutineStart.LAZY) { cancelling.replaceAssistantTurns { entries(turn("cancelled")) } }
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
        repository.replaceAssistantTurns { entries(turn("old")) }
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
                controlled.replaceAssistantTurns { entries(turn("new")) }
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

    @Test fun deleteTurnRemovesRowMediaAndDiagnosticRootAndSurvivesReopen() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"))
        repository.appendTurn("chat", turn("b"))
        repository.appendTurn("chat", turn("c"))
        val rows = database.turnDao().listForChat("chat")
        val discarded = mutableListOf<String>()
        repository.onDiagnosticDiscard = { discarded.add(it) }
        repository.deleteTurn("chat", rows[1].id)
        assertEquals(listOf("operation-b"), discarded)
        assertEquals(listOf(turn("a"), turn("c")), repository.loadTurns("chat"))
        val names = files().keys
        assertTrue(names.any { it.startsWith("chat/${rows[0].id}_") })
        assertTrue(names.none { it.startsWith("chat/${rows[1].id}_") })
        assertTrue(names.any { it.startsWith("chat/${rows[2].id}_") })
        reopen()
        assertEquals(listOf(turn("a"), turn("c")), repository.loadTurns("chat"))
        assertEquals(turn("a").modelMessages() + turn("c").modelMessages(), repository.loadModelHistory("chat"))
    }

    @Test fun deleteTurnsAfterKeepsTargetTurnAndSurvivesReopen() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"))
        repository.appendTurn("chat", turn("b"))
        repository.appendTurn("chat", turn("c"))
        val rows = database.turnDao().listForChat("chat")
        val discarded = mutableListOf<String>()
        repository.onDiagnosticDiscard = { discarded.add(it) }
        repository.deleteTurnsAfter("chat", rows[0].id)
        assertEquals(setOf("operation-b", "operation-c"), discarded.toSet())
        assertEquals(listOf(turn("a")), repository.loadTurns("chat"))
        val names = files().keys
        assertTrue(names.any { it.startsWith("chat/${rows[0].id}_") })
        assertTrue(names.none { it.startsWith("chat/${rows[1].id}_") || it.startsWith("chat/${rows[2].id}_") })
        reopen()
        assertEquals(listOf(turn("a")), repository.loadTurns("chat"))
    }

    @Test fun appendAcceptsCallerTurnIdAndLoadTurnEntriesExposesIt() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"), turnId = "turn-a")
        repository.appendTurn("chat", turn("b"))
        val entries = repository.loadTurnEntries("chat")
        assertEquals("turn-a", entries[0].id)
        assertEquals(listOf(turn("a"), turn("b")), entries.map { it.turn })
        repository.deleteTurn("chat", "turn-a")
        assertEquals(listOf("b"), repository.loadTurnEntries("chat").map { it.turn.userText })
    }

    @Test fun replacementPreservesTurnIdsAndDeletionReportsThemToMirror() = runBlocking {
        val id = ChatRepository.ASSISTANT_CHAT_ID
        repository.replaceAssistantTurns {
            listOf(StoredTurnEntry("turn-a", turn("a")), StoredTurnEntry("turn-b", turn("b")))
        }
        assertEquals(listOf("turn-a", "turn-b"), database.turnDao().listForChat(id).map { it.id })
        repository.replaceAssistantTurns { listOf(StoredTurnEntry("turn-b", turn("b"))) }
        assertEquals(listOf("turn-b"), database.turnDao().listForChat(id).map { it.id })
        val deleted = mutableMapOf<String, Set<String>>()
        repository.onTurnsDeleted = { chatId, turnIds -> deleted[chatId] = turnIds }
        repository.deleteTurn(id, "turn-b")
        assertEquals(mapOf(id to setOf("turn-b")), deleted)
    }

    @Test fun abortedRewriteOfPreservedIdsKeepsCommittedBytes() = runBlocking {
        val id = ChatRepository.ASSISTANT_CHAT_ID
        repository.replaceAssistantTurns { listOf(StoredTurnEntry("turn-a", turn("a"))) }
        val previous = files()
        abort("INSERT", "turns")
        assertNotNull(runCatching {
            repository.replaceAssistantTurns {
                listOf(StoredTurnEntry("turn-a", turn("a")), StoredTurnEntry("turn-b", turn("b")))
            }
        }.exceptionOrNull())
        // Сохранённый id перезаписан теми же байтами, новые файлы стёрты.
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("a")), repository.loadTurns(id))
        allowWrites()
        repository.replaceAssistantTurns {
            listOf(StoredTurnEntry("turn-a", turn("a")), StoredTurnEntry("turn-b", turn("b")))
        }
        assertEquals(listOf(turn("a"), turn("b")), repository.loadTurns(id))
    }

    @Test fun deletionQueuedBehindRewriteCommitsAfterItWithoutResurrection() = runBlocking {
        val id = ChatRepository.ASSISTANT_CHAT_ID
        val mirror = mutableListOf(StoredTurnEntry("turn-a", turn("a")), StoredTurnEntry("turn-b", turn("b")))
        repository.onTurnsDeleted = { _, turnIds ->
            synchronized(mirror) { mirror.removeAll { entry -> entry.id in turnIds } }
        }
        repository.replaceAssistantTurns { synchronized(mirror) { mirror.toList() } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        onQuery = { sql ->
            if (sql.contains("INSERT", ignoreCase = true) && sql.contains("turns")) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val rewrite = launch(Dispatchers.IO) {
            repository.replaceAssistantTurns { synchronized(mirror) { mirror.toList() } }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // Стирание встаёт в очередь за перезаписью и сносит ход и из БД,
            // и из живого зеркала — следующий снимок его не воскрешает.
            val deletion = launch(Dispatchers.IO) { repository.deleteTurn(id, "turn-b") }
            assertFalse(deletion.isCompleted)
            release.countDown()
            rewrite.join()
            deletion.join()
        } finally {
            release.countDown()
            onQuery = null
        }
        assertEquals(listOf("a"), repository.loadTurns(id).map { it.userText })
        assertEquals(listOf("turn-a"), synchronized(mirror) { mirror.map { it.id } })
    }

    @Test fun deletingMissingTurnIsIdempotentNoOp() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"))
        val previous = files()
        val discarded = mutableListOf<String>()
        repository.onDiagnosticDiscard = { discarded.add(it) }
        repository.deleteTurn("chat", "missing")
        repository.deleteTurnsAfter("chat", "missing")
        assertTrue(discarded.isEmpty())
        assertEquals(listOf(turn("a")), repository.loadTurns("chat"))
        assertEquals(previous, files())
    }

    @Test fun failedTurnDeleteKeepsRowsAndMediaUntilDatabaseCommit() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"))
        repository.appendTurn("chat", turn("b"))
        val rows = database.turnDao().listForChat("chat")
        val previous = files()
        val discarded = mutableListOf<String>()
        repository.onDiagnosticDiscard = { discarded.add(it) }
        abort("DELETE", "turns")
        assertNotNull(runCatching { repository.deleteTurn("chat", rows[1].id) }.exceptionOrNull())
        // Диагностика стирается до транзакции (fail-closed, как у deleteChat):
        // сбой БД оставляет ход и медиа, но не откатывает стирание диагностики.
        assertEquals(listOf("operation-b"), discarded)
        assertEquals(previous, files())
        reopen()
        assertEquals(listOf(turn("a"), turn("b")), repository.loadTurns("chat"))
        allowWrites()
        repository.deleteTurn("chat", rows[1].id)
        assertEquals(listOf("operation-b", "operation-b"), discarded)
        reopen()
        assertEquals(listOf(turn("a")), repository.loadTurns("chat"))
        assertTrue(files().keys.none { it.startsWith("chat/${rows[1].id}_") })
    }

    @Test fun diagnosticDiscardFailureKeepsTurnLiveAndAllowsRetry() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"))
        repository.appendTurn("chat", turn("b"))
        val rows = database.turnDao().listForChat("chat")
        val previous = files()
        val attempts = mutableListOf<String>()
        repository.onDiagnosticDiscard = { root ->
            attempts.add(root)
            if (attempts.size == 1) error("diagnostic failure")
        }
        assertTrue(runCatching { repository.deleteTurn("chat", rows[1].id) }.isFailure)
        assertEquals(listOf("operation-b"), attempts)
        assertEquals(listOf(turn("a"), turn("b")), repository.loadTurns("chat"))
        assertEquals(previous, files())
        repository.deleteTurn("chat", rows[1].id)
        assertEquals(listOf("operation-b", "operation-b"), attempts)
        assertEquals(listOf(turn("a")), repository.loadTurns("chat"))
    }

    @Test fun cancellationDuringTurnDeleteStillRemovesRowAndMedia() = runBlocking {
        repository.createChat(AssistMode.CHAT, "chat", "chat")
        repository.appendTurn("chat", turn("a"))
        repository.appendTurn("chat", turn("b"))
        val rows = database.turnDao().listForChat("chat")
        val deleting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        repository.onDiagnosticDiscard = {
            deleting.countDown()
            check(resume.await(10, TimeUnit.SECONDS))
        }
        val job = async(Dispatchers.IO) { repository.deleteTurn("chat", rows[1].id) }
        try {
            assertTrue(deleting.await(10, TimeUnit.SECONDS))
            job.cancel()
            resume.countDown()
            job.cancelAndJoin()
            assertEquals(listOf(turn("a")), repository.loadTurns("chat"))
            assertTrue(files().keys.none { it.startsWith("chat/${rows[1].id}_") })
        } finally {
            resume.countDown()
        }
    }
}
