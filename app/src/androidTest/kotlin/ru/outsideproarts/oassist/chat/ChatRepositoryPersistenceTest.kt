package ru.outsideproarts.oassist.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.StoredTurn

@RunWith(AndroidJUnit4::class)
class ChatRepositoryPersistenceTest {
    private val imageTurn = StoredTurn(userImages = listOf(ImagePart("AQID")))

    @Test
    fun deletingChatDuringAppendLeavesNoTurnOrMedia() = runBlocking {
        val pause = AtomicBoolean(false)
        val encoded = CountDownLatch(1)
        val resume = CountDownLatch(1)
        ChatPersistenceFixture(clock = {
            if (pause.get()) {
                encoded.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
            }
            1L
        }).use { fixture ->
            val repository = fixture.repository
            val id = repository.createChat(AssistMode.CHAT, "test")
            pause.set(true)
            val deletedRoots = mutableSetOf<String>()
            repository.onDiagnosticDelete = { _, roots -> deletedRoots.addAll(roots) }
            val append = async(Dispatchers.IO) {
                repository.appendTurn(id, imageTurn.copy(diagnosticOperationId = "operation"))
            }
            try {
                assertTrue(encoded.await(10, TimeUnit.SECONDS))
                val deletion = async(Dispatchers.IO) { repository.deleteChat(id) }
                // A correct implementation may wait for the in-flight append before deleting.
                withTimeoutOrNull(1_000) { deletion.await() }
                resume.countDown()
                append.await()
                deletion.await()
                assertNull(repository.getChat(id))
                assertTrue(fixture.database.turnDao().listForChat(id).isEmpty())
                assertFalse(java.io.File(fixture.mediaRoot, id).exists())
                assertEquals(setOf("operation"), deletedRoots)
            } finally {
                resume.countDown()
            }
        }
    }

    @Test
    fun clearingAllDuringAppendLeavesNoTurnOrMedia() = runBlocking {
        val pause = AtomicBoolean(false)
        val encoded = CountDownLatch(1)
        val resume = CountDownLatch(1)
        ChatPersistenceFixture(clock = {
            if (pause.get()) {
                encoded.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
            }
            1L
        }).use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "test")
            pause.set(true)
            val append = async(Dispatchers.IO) { fixture.repository.appendTurn(id, imageTurn) }
            try {
                assertTrue(encoded.await(10, TimeUnit.SECONDS))
                val clear = async(Dispatchers.IO) { fixture.repository.clearAll() }
                withTimeoutOrNull(1_000) { clear.await() }
                resume.countDown()
                append.await()
                clear.await()
                assertNull(fixture.repository.getChat(id))
                assertTrue(fixture.database.turnDao().listForChat(id).isEmpty())
                assertFalse(fixture.mediaRoot.exists())
            } finally {
                resume.countDown()
            }
        }
    }

    @Test
    fun appendToMissingChatFailsWithoutCreatingFiles() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val failure = runCatching { fixture.repository.appendTurn("missing", imageTurn) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.database.turnDao().listForChat("missing").isEmpty())
            assertFalse(fixture.mediaRoot.exists())
        }
    }

    @Test
    fun failedInsertRemovesOnlyTheUncommittedTurnFiles() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "test")
            fixture.repository.appendTurn(id, imageTurn)
            fixture.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_turn_insert BEFORE INSERT ON turns BEGIN SELECT RAISE(ABORT, 'test failure'); END",
            )
            assertTrue(runCatching { fixture.repository.appendTurn(id, imageTurn) }.isFailure)
            assertEquals(listOf(imageTurn), fixture.repository.loadTurns(id))
            assertEquals(1, java.io.File(fixture.mediaRoot, id).listFiles()?.size)
        }
    }

    @Test
    fun errorAfterEncodingRemovesUncommittedFiles() = runBlocking {
        val fail = AtomicBoolean(false)
        ChatPersistenceFixture(clock = { check(!fail.get()); 1L }).use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "test")
            fail.set(true)
            assertTrue(runCatching { fixture.repository.appendTurn(id, imageTurn) }.isFailure)
            assertTrue(fixture.database.turnDao().listForChat(id).isEmpty())
            assertFalse(java.io.File(fixture.mediaRoot, id).exists())
        }
    }

    @Test
    fun cancellationBeforeInsertRemovesUncommittedFiles() = runBlocking {
        val pause = AtomicBoolean(false)
        val encoded = CountDownLatch(1)
        val resume = CountDownLatch(1)
        ChatPersistenceFixture(clock = {
            if (pause.get()) {
                encoded.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
            }
            1L
        }).use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "test")
            pause.set(true)
            val append = async(Dispatchers.IO) { fixture.repository.appendTurn(id, imageTurn) }
            try {
                assertTrue(encoded.await(10, TimeUnit.SECONDS))
                append.cancel()
                resume.countDown()
                append.cancelAndJoin()
                assertTrue(fixture.database.turnDao().listForChat(id).isEmpty())
                assertFalse(java.io.File(fixture.mediaRoot, id).exists())
            } finally {
                resume.countDown()
            }
        }
    }

    @Test
    fun cancellationDuringDeleteStillRemovesTurnAndMedia() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "test")
            fixture.repository.appendTurn(id, imageTurn)
            val deleting = CountDownLatch(1)
            val resume = CountDownLatch(1)
            fixture.repository.onDiagnosticDelete = { _, _ ->
                deleting.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
            }
            val job = async(Dispatchers.IO) { fixture.repository.deleteChat(id) }
            try {
                assertTrue(deleting.await(10, TimeUnit.SECONDS))
                job.cancel()
                resume.countDown()
                job.cancelAndJoin()
                assertNull(fixture.repository.getChat(id))
                assertTrue(fixture.database.turnDao().listForChat(id).isEmpty())
                assertFalse(java.io.File(fixture.mediaRoot, id).exists())
            } finally {
                resume.countDown()
            }
        }
    }

    @Test
    fun diagnosticDeleteFailureKeepsChatLiveAndAllowsRetry() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val repository = fixture.repository
            val id = repository.createChat(AssistMode.CHAT, "test")
            repository.appendTurn(id, imageTurn.copy(diagnosticOperationId = "operation"))
            val generation = repository.diagnosticGeneration(id)
            val attempts = mutableListOf<Set<String>>()
            repository.onDiagnosticDelete = { _, roots ->
                attempts.add(roots)
                if (attempts.size == 1) error("diagnostic failure")
            }
            val bound = mutableListOf<String>()
            repository.onDiagnosticBind = { _, operation -> bound.add(operation) }

            assertTrue(runCatching { repository.deleteChat(id) }.isFailure)
            assertEquals(generation, repository.diagnosticGeneration(id))
            assertTrue(repository.getChat(id) != null)
            assertEquals(1, fixture.database.turnDao().listForChat(id).size)
            assertTrue(java.io.File(fixture.mediaRoot, id).exists())
            repository.bindDiagnosticOperation(id, "after-failure", generation)
            assertEquals(listOf("after-failure"), bound)

            repository.deleteChat(id)
            assertEquals(listOf(setOf("operation"), setOf("operation")), attempts)
            assertNull(repository.getChat(id))
            assertTrue(fixture.database.turnDao().listForChat(id).isEmpty())
            assertFalse(java.io.File(fixture.mediaRoot, id).exists())
        }
    }
}
