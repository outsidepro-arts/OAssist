package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    @Test
    fun toolCall_event_carriesArguments() = runBlocking {
        val narrator = Narrator()
        val events = mutableListOf<Narrator.NarrativeEvent>()
        val job = launch(Dispatchers.Unconfined) {
            narrator.events.collect { events.add(it) }
        }
        narrator.onToolCall("alarm_set", """{"hour":7,"minute":0}""", "Установка будильника 7:00")
        job.cancel()
        assertEquals(1, events.size)
        val call = events[0] as Narrator.NarrativeEvent.ToolCall
        assertEquals("alarm_set", call.toolName)
        assertEquals("Установка будильника 7:00", call.arguments)
    }

    @Test
    fun toolCall_event_fallbackToRawJson_whenSummaryBlank() = runBlocking {
        val narrator = Narrator()
        val events = mutableListOf<Narrator.NarrativeEvent>()
        val job = launch(Dispatchers.Unconfined) {
            narrator.events.collect { events.add(it) }
        }
        narrator.onToolCall("web_search", """{"query":"test"}""", "")
        job.cancel()
        val call = events[0] as Narrator.NarrativeEvent.ToolCall
        assertEquals("""{"query":"test"}""", call.arguments)
    }

    @Test
    fun toolResult_event_carriesSuccessFlag() = runBlocking {
        val narrator = Narrator()
        val events = mutableListOf<Narrator.NarrativeEvent>()
        val job = launch(Dispatchers.Unconfined) {
            narrator.events.collect { events.add(it) }
        }
        narrator.onToolResult("sms_send", "нет разрешения", success = false)
        narrator.onToolResult("alarm_set", "будильник поставлен", success = true)
        job.cancel()
        assertEquals(2, events.size)
        val err = events[0] as Narrator.NarrativeEvent.ToolResult
        assertEquals(false, err.success)
        assertEquals("нет разрешения", err.output)
        val ok = events[1] as Narrator.NarrativeEvent.ToolResult
        assertEquals(true, ok.success)
    }

    @Test
    fun disabled_narrator_emitsNoEvents() = runBlocking {
        val narrator = Narrator().apply { enabled = false }
        val events = mutableListOf<Narrator.NarrativeEvent>()
        val job = launch(Dispatchers.Unconfined) {
            narrator.events.collect { events.add(it) }
        }
        narrator.onToolCall("alarm_set", "{}", "summary")
        narrator.onToolResult("alarm_set", "ok", true)
        job.cancel()
        assertTrue(events.isEmpty())
    }
}
