package ru.outsideproarts.oassist.feature.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatTurnSaverTest {
    @Test
    fun turnsCompletedDuringFirstSaveUseOneChatInOrder() = runBlocking {
        val releaseCreation = CompletableDeferred<Unit>()
        val saved = mutableListOf<Pair<String, String>>()
        var created = 0
        var chatId: String? = null
        val saver = ChatTurnSaver(this)

        fun record(turn: String): Job = saver.save(
            currentId = chatId,
            create = {
                val id = "chat-${++created}"
                releaseCreation.await()
                id
            },
            append = { id ->
                saved.add(id to turn)
                if (chatId == null) chatId = id
            },
        )

        record("first")
        yield()
        val second = record("second")
        yield()
        releaseCreation.complete(Unit)
        second.join()

        assertEquals(1, created)
        assertEquals(listOf("chat-1" to "first", "chat-1" to "second"), saved)
    }

    @Test
    fun existingChatAppendsTurnsInOrder() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val saver = ChatTurnSaver(this)
        val first = saver.save("chat", create = { error("chat already exists") }) { id ->
            releaseFirst.await()
            saved.add("$id:first")
        }
        yield()
        val second = saver.save("chat", create = { error("chat already exists") }) { id ->
            saved.add("$id:second")
        }
        yield()
        assertEquals(emptyList<String>(), saved)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("chat:first", "chat:second"), saved)
    }

    @Test
    fun switchingChatsDoesNotWaitForOrReusePreviousChat() = runBlocking {
        val releaseOld = CompletableDeferred<Unit>()
        val saved = mutableListOf<Pair<String, String>>()
        val oldSaver = ChatTurnSaver(this)
        val old = oldSaver.save(null, create = {
            releaseOld.await()
            "old"
        }) { saved.add(it to "old turn") }
        yield()

        val newSaver = ChatTurnSaver(this)
        val new = newSaver.save(null, create = { "new" }) { saved.add(it to "new turn") }
        new.join()
        assertEquals(listOf("new" to "new turn"), saved)

        releaseOld.complete(Unit)
        old.join()
        assertEquals(listOf("new" to "new turn", "old" to "old turn"), saved)
    }

    @Test
    fun reopeningChatKeepsItsPendingSavesInOrder() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val savers = ChatTurnSavers(this)
        val original = savers.openChat("chat")
        original.save("chat", create = { error("chat already exists") }) {
            releaseFirst.await()
            saved.add("first")
        }
        yield()
        original.save("chat", create = { error("chat already exists") }) { saved.add("second") }

        savers.newChat()
        val other = savers.openChat("other")
        other.save("other", create = { error("chat already exists") }) { saved.add("other") }.join()
        val reopened = savers.openChat("chat")
        val third = reopened.save("chat", create = { error("chat already exists") }) { saved.add("third") }
        yield()
        assertEquals(listOf("other"), saved)

        releaseFirst.complete(Unit)
        third.join()
        assertEquals(listOf("other", "first", "second", "third"), saved)
    }

    @Test
    fun openingPublishedIdWhileCreationIsSuspendedReusesItsQueue() = runBlocking {
        val releaseCreation = CompletableDeferred<Unit>()
        val published = CompletableDeferred<String>()
        val saved = mutableListOf<String>()
        val savers = ChatTurnSavers(this)
        val original = savers.current
        original.save(null, create = {
            val id = "new-chat"
            savers.register(id, original)
            published.complete(id)
            releaseCreation.await()
            id
        }) { saved.add("first") }
        val id = published.await()
        val reopened = savers.openChat(id)
        val second = reopened.save(id, create = { error("chat already exists") }) { saved.add("second") }
        yield()
        assertEquals(emptyList<String>(), saved)
        releaseCreation.complete(Unit)
        second.join()
        assertEquals(listOf("first", "second"), saved)
    }

    @Test
    fun cancellingSessionCancelsPendingSaves() = runBlocking {
        val session = Job(coroutineContext[Job])
        val saver = ChatTurnSaver(CoroutineScope(coroutineContext + session))
        val started = CompletableDeferred<Unit>()
        var appended = false
        saver.save(null, create = {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            "chat"
        }) { appended = true }
        started.await()
        session.cancelAndJoin()
        assertFalse(appended)
        assertFalse(saver.isSaving)
    }
}
