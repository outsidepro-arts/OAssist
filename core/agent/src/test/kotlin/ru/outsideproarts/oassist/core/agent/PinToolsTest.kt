package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.PinChange

class PinToolsTest {

    private fun args(messageId: String, note: String? = null) = buildJsonObject {
        put("message_id", messageId)
        note?.let { put("note", it) }
    }

    @Test
    fun pin_returnsChangeWithNote() = runTest {
        val result = MessagePinTool().execute(args("turn-1:0", "референс для сверки"))

        assertTrue(result.success)
        assertEquals(PinChange("turn-1:0", pinned = true, note = "референс для сверки"), result.pinChange)
    }

    @Test
    fun pin_currentTurnAddress_isAccepted() = runTest {
        val result = MessagePinTool().execute(args("current:0"))

        assertTrue(result.success)
        assertEquals(PinChange("current:0", pinned = true, note = null), result.pinChange)
    }

    @Test
    fun pin_noteIsCapped() = runTest {
        val result = MessagePinTool().execute(args("turn-1:0", "х".repeat(500)))

        assertEquals(200, result.pinChange?.note?.length)
    }

    @Test
    fun pin_invalidAddress_failsWithoutChange() = runTest {
        for (bad in listOf("без-индекса", "turn:", "turn:-1", "")) {
            val result = MessagePinTool().execute(args(bad))
            assertFalse("адрес $bad принят", result.success)
            assertNull(result.pinChange)
        }
    }

    @Test
    fun pin_nonStringAddress_failsWithoutChange() = runTest {
        val result = MessagePinTool().execute(buildJsonObject { put("message_id", 42) })

        assertFalse(result.success)
        assertNull(result.pinChange)
    }

    @Test
    fun unpin_returnsUnpinChange() = runTest {
        val result = MessageUnpinTool().execute(args("turn-1:2"))

        assertTrue(result.success)
        assertEquals(PinChange("turn-1:2", pinned = false, note = null), result.pinChange)
    }

    @Test
    fun unpin_invalidAddress_failsWithoutChange() = runTest {
        val result = MessageUnpinTool().execute(args("мусор"))

        assertFalse(result.success)
        assertNull(result.pinChange)
    }

    @Test
    fun confirmationSummaries_nameTheAction() {
        assertEquals(
            "Закрепить сообщение turn-1:0: референс",
            MessagePinTool().describeForConfirmation(args("turn-1:0", "референс")),
        )
        assertEquals(
            "Закрепить сообщение turn-1:0",
            MessagePinTool().describeForConfirmation(args("turn-1:0")),
        )
        assertEquals(
            "Открепить сообщение turn-1:2",
            MessageUnpinTool().describeForConfirmation(args("turn-1:2")),
        )
    }

    @Test
    fun toolsAreRegisteredInTheCatalog() {
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue("message_pin не в каталоге", "message_pin" in names)
        assertTrue("message_unpin не в каталоге", "message_unpin" in names)
    }
}
