package ru.outsideproarts.oassist.core.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolNameTest {

    @Test
    fun asciiName_prefixedWithShortId() {
        assertEquals("mcp_abc123__read_file", McpTool.displayName("abc123", "read_file"))
    }
    @Test
    fun cyrillicAndSpaces_sanitized() {
        val name = McpTool.displayName("srv-1", "прочитать файл!")
        assertTrue("got: $name", name.matches(Regex("[a-zA-Z0-9_-]+")))
        assertTrue(name.startsWith("mcp_srv-1__"))
    }

    @Test
    fun longName_cappedAt64() {
        val name = McpTool.displayName("server-id-1234567890", "a".repeat(100))
        assertTrue(name.length <= 64)
    }

    @Test
    fun stableAcrossServerRename() {
        assertEquals(
            McpTool.displayName("id-42", "read"),
            McpTool.displayName("id-42", "read"),
        )
    }

    @Test
    fun blankInputs_fallBack() {
        assertEquals("mcp_srv__tool", McpTool.displayName("", ""))
    }
}
