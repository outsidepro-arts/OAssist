package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentSoundToolCallTest {

    @Test
    fun readTools_playReadSound() {
        listOf(
            "device_state", "ui_get_screen", "web_fetch", "contact_find",
            "memory_get", "notify_list", "call_log_list", "app_list",
            "shell_get_setting",
        ).forEach { name ->
            assertEquals("тул $name", AgentSound.TOOL_CALL_READ, AgentSound.toolCallSound(name))
        }
    }

    @Test
    fun writeTools_playWriteSound() {
        listOf(
            "alarm_set", "ui_click", "memory_put", "sms_send",
            "notify_open", "app_launch", "shell_exec",
        ).forEach { name ->
            assertEquals("тул $name", AgentSound.TOOL_CALL_WRITE, AgentSound.toolCallSound(name))
        }
    }

    @Test
    fun toolsWithoutAccessClass_areSilent() {
        assertNull(AgentSound.toolCallSound("mcp_srv1__fetch"))
        assertNull(AgentSound.toolCallSound("bye"))
        assertNull(AgentSound.toolCallSound("no_such_tool"))
    }

    @Test
    fun resNames_matchRawResources() {
        assertEquals("tool_call_read", AgentSound.TOOL_CALL_READ.resName)
        assertEquals("tool_call_write", AgentSound.TOOL_CALL_WRITE.resName)
    }
}
