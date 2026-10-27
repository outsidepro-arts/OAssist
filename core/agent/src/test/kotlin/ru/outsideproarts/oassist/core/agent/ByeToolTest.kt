package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByeToolTest {

    @Test
    fun execute_returnsSuccess() {
        val tool = ByeTool()
        val result = runBlocking { tool.execute(kotlinx.serialization.json.buildJsonObject { }) }
        assertTrue(result.success)
    }

    @Test
    fun schema_isServiceGroupWithFarewell() {
        val tool = ByeTool()
        assertEquals("bye", tool.schema.name)
        assertEquals(ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE, tool.groupKey)
        assertTrue(tool.schema.parameters.toString().contains("\"farewell\""))
        assertTrue(tool.schema.parameters.toString().contains("\"required\""))
    }

    @Test
    fun ownNotificationDetection() {
        assertTrue(isOwnNotification("ru.outsideproarts.oassist", "ru.outsideproarts.oassist"))
        assertFalse(isOwnNotification("org.telegram", "ru.outsideproarts.oassist"))
        assertFalse(isOwnNotification(null, "ru.outsideproarts.oassist"))
        assertTrue(isOwnNotification(null, null))
    }
}
