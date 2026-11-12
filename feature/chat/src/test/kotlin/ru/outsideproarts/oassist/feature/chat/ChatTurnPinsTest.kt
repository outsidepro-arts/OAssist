package ru.outsideproarts.oassist.feature.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.PinChange

class ChatTurnPinsTest {

    /**
     * Регрессия блокера: закрепление, пришедшее во время отложенной записи хода,
     * должно дождаться дозаписи и не потеряться (раньше писалось напрямую в
     * обход очереди и молча отбрасывалось, пока ход ещё не записан).
     */
    @Test
    fun pinWriteWaitsForPendingAppendAndThenApplies() = runBlocking {
        val order = mutableListOf<String>()
        val appendGate = CompletableDeferred<Unit>()
        val saver = ChatTurnSaver(this)
        // Дозапись хода ещё в очереди, когда приходит закрепление.
        saver.save(currentId = "chat", create = { error("already exists") }) {
            order.add("append")
            appendGate.await()
        }
        val pins = ChatTurnPins(saver, "chat") { chatId, turnId, messageIndex, pinned, note ->
            order.add("setPin")
            assertEquals("chat", chatId)
            assertEquals("turn-1", turnId)
            assertEquals(0, messageIndex)
            assertTrue(pinned)
            assertEquals("референс", note)
            true
        }

        assertTrue(pins.queue(PinChange("turn-1:0", pinned = true, note = "референс")))
        appendGate.complete(Unit)
        assertTrue(saver.awaitSaved())

        // Порядок строгий: сначала дозапись хода, потом флаг.
        assertEquals(listOf("append", "setPin"), order)
    }

    @Test
    fun queueRejectsUnparsableCurrentAndMissingChatAddresses() = runBlocking {
        val saver = ChatTurnSaver(this)
        val writes = mutableListOf<String>()
        val withoutChat = ChatTurnPins(saver, null) { _, _, _, _, _ -> writes.add("write"); true }

        assertFalse(withoutChat.queue(PinChange("мусор", pinned = true)))
        assertFalse(withoutChat.queue(PinChange("current:0", pinned = true)))
        assertFalse(withoutChat.queue(PinChange("turn-1:0", pinned = true)))
        assertTrue(writes.isEmpty())

        val withChat = ChatTurnPins(saver, "chat") { _, _, _, _, _ -> writes.add("write"); true }
        assertTrue(withChat.queue(PinChange("turn-1:0", pinned = false)))
        assertTrue(saver.awaitSaved())
        assertEquals(listOf("write"), writes)
    }

    @Test
    fun pinWaitsBehindFailedHeadUntilRetryWithoutOvertaking() = runBlocking {
        val order = mutableListOf<String>()
        var failFirst = true
        val saver = ChatTurnSaver(this)
        val head = saver.save(currentId = "chat", create = { error("already exists") }) {
            if (failFirst) {
                failFirst = false
                throw java.io.IOException("disk full")
            }
            order.add("append")
        }
        val pins = ChatTurnPins(saver, "chat") { _, _, _, _, _ -> order.add("setPin"); true }

        assertTrue(pins.queue(PinChange("turn-1:0", pinned = true)))
        head.join()
        // Сбойная голова очереди не даёт пину обойти дозапись целевого хода.
        assertEquals(emptyList<String>(), order)
        saver.retry().join()
        assertEquals(listOf("append", "setPin"), order)
    }
}
