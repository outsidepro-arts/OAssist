package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePinTest {

    private fun turn() = StoredTurn(
        userText = "вопрос",
        turn = listOf(
            ChatMessage(ChatRole.ASSISTANT, "делаю"),
            ChatMessage(ChatRole.TOOL, "скриншот", toolCallId = "c1"),
        ),
    )

    @Test
    fun `userPin попадает в реплику пользователя modelMessages`() {
        val stored = turn().copy(userPin = MessagePin("референс"))
        val user = stored.modelMessages().first()
        assertEquals(MessagePin("референс"), user.pin)
    }

    @Test
    fun `withMessagePin индекс 0 закрепляет реплику пользователя`() {
        val pinned = turn().withMessagePin(0, pinned = true, note = "фото для сверки")
        assertEquals(MessagePin("фото для сверки"), pinned.userPin)
        assertNull(pinned.turn.single { it.role == ChatRole.TOOL }.pin)
    }

    @Test
    fun `withMessagePin индекс транскрипта закрепляет сообщение транскрипта`() {
        val pinned = turn().withMessagePin(2, pinned = true)
        assertNull(pinned.userPin)
        assertNull(pinned.turn.first().pin)
        assertEquals(MessagePin(), pinned.turn.last().pin)
    }

    @Test
    fun `withMessagePin снимает закрепление`() {
        val pinned = turn().withMessagePin(1, pinned = true, note = "было")
        val cleared = pinned.withMessagePin(1, pinned = false)
        assertNull(cleared.turn.first().pin)
    }

    @Test
    fun `withMessagePin вне хода — no-op`() {
        val stored = turn()
        assertEquals(stored, stored.withMessagePin(3, pinned = true))
        assertEquals(stored, stored.withMessagePin(-1, pinned = true))
    }

    @Test
    fun `withPinChanges применяет только закрепления текущего хода`() {
        val changes = listOf(
            PinChange(currentMessageId(0), pinned = true, note = "референс"),
            PinChange(turnMessageId("past-1", 0), pinned = true),
            PinChange("мусор", pinned = true),
        )
        val stored = turn().withPinChanges(changes)
        assertEquals(MessagePin("референс"), stored.userPin)
        assertTrue(stored.turn.all { it.pin == null })
    }

    @Test
    fun `адреса сообщений разбираются обратно`() {
        assertEquals(MessageRef("turn-1", 0), parseMessageId(turnMessageId("turn-1", 0)))
        assertEquals(MessageRef(CURRENT_TURN_ID, 2), parseMessageId(currentMessageId(2)))
        assertNull(parseMessageId("без-индекса"))
        assertNull(parseMessageId("turn:"))
        assertNull(parseMessageId("turn:-1"))
    }

    @Test
    fun `modelMessages проставляет адреса по позициям`() {
        val entry = StoredTurnEntry("turn-9", turn())
        val messages = entry.modelMessages()
        assertEquals(listOf("turn-9:0", "turn-9:1", "turn-9:2"), messages.map { it.id })
        assertEquals(turn().modelMessages().map { it.copy(id = null) }, messages.map { it.copy(id = null) })
    }

    @Test
    fun `адреса entry переносят закрепления из транскрипта`() {
        val entry = StoredTurnEntry("turn-9", turn().withMessagePin(2, pinned = true, note = "скрин"))
        val tool = entry.modelMessages()[2]
        assertEquals(MessagePin("скрин"), tool.pin)
        assertEquals("turn-9:2", tool.id)
    }
}
