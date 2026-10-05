package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarratorTest {

    @Test
    fun narrator_isEnabledByDefault() {
        val narrator = Narrator()
        assertTrue(narrator.enabled)
    }

    @Test
    fun disabled_narrator_canBeToggled() {
        val narrator = Narrator()
        narrator.enabled = false
        assertFalse(narrator.enabled)
        narrator.enabled = true
        assertTrue(narrator.enabled)
    }

    @Test
    fun timelineEntry_holdsTimestamp() {
        val now = System.currentTimeMillis()
        val entry = Narrator.TimelineEntry.ToolCall("web_search", "{}", now)
        assertEquals(now, entry.timestamp)
        assertEquals("web_search", entry.toolName)
    }

    @Test
    fun timelineEntry_toolResult() {
        val entry = Narrator.TimelineEntry.ToolResult("alarm_set", "ok", 123L)
        assertEquals("alarm_set", entry.toolName)
        assertEquals("ok", entry.output)
        assertEquals(123L, entry.timestamp)
    }
}
