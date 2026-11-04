package ru.outsideproarts.oassist.core.agent

import android.app.NotificationManager.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DndRequestTest {
    private class Fixture {
        var access = true
        var observed = INTERRUPTION_FILTER_ALL
        val writes = mutableListOf<Int>()
        var reads = 0
        var failure: Exception? = null
        var readFailure: Exception? = null
        fun send(enabled: Boolean = false, appRuleOnly: Boolean = true) = sendDndRequest(
            enabled, appRuleOnly, { access },
            { writes += it; failure?.let { error -> throw error } },
            { reads++; readFailure?.let { throw it }; observed },
            { "resource:$it" },
        )
    }

    @Test fun `pre35 reports observed filter after one request without claiming convergence`() {
        val f = Fixture()
        val result = f.send(appRuleOnly = false)
        val json = Json.parseToJsonElement(result.output).jsonObject
        assertTrue(result.success)
        assertEquals(listOf(INTERRUPTION_FILTER_ALL), f.writes)
        assertEquals(1, f.reads)
        assertEquals("global", json.getValue("scope").jsonPrimitive.content)
        assertEquals("all", json.getValue("observedGlobalFilter").jsonPrimitive.content)
        assertFalse(json.getValue("globalOutcomeConfirmed").jsonPrimitive.boolean)
        assertFalse(json.containsKey("nextStep"))
    }

    @Test fun `other owner filters remain visible when own rule is withdrawn`() {
        for ((filter, name) in listOf(INTERRUPTION_FILTER_NONE to "none",
            INTERRUPTION_FILTER_PRIORITY to "priority", INTERRUPTION_FILTER_ALARMS to "alarms")) {
            val f = Fixture().apply { observed = filter }
            val result = f.send()
            val json = Json.parseToJsonElement(result.output).jsonObject
            assertTrue(result.success)
            assertEquals(listOf(INTERRUPTION_FILTER_ALL), f.writes)
            assertEquals("app_rule", json.getValue("scope").jsonPrimitive.content)
            assertEquals(name, json.getValue("observedGlobalFilter").jsonPrimitive.content)
            assertEquals(filter, json.getValue("observedGlobalFilterValue").jsonPrimitive.int)
            assertTrue(json.containsKey("limitation"))
            assertTrue(json.containsKey("nextStep"))
        }
    }

    @Test fun `late application does not rewrite snapshot or trigger another action`() {
        val f = Fixture()
        val result = f.send(enabled = true)
        f.observed = INTERRUPTION_FILTER_NONE
        val json = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("none", json.getValue("requestedFilter").jsonPrimitive.content)
        assertEquals("all", json.getValue("observedGlobalFilter").jsonPrimitive.content)
        assertTrue(json.containsKey("nextStep"))
        assertEquals(listOf(INTERRUPTION_FILTER_NONE), f.writes)
        assertEquals(1, f.reads)
    }

    @Test fun `unknown or unreadable filter does not undo or retry accepted request`() {
        for (f in listOf(Fixture().apply { observed = INTERRUPTION_FILTER_UNKNOWN },
            Fixture().apply { readFailure = SecurityException() }, Fixture().apply { observed = 42 })) {
            val result = f.send()
            val json = Json.parseToJsonElement(result.output).jsonObject
            assertTrue(result.success)
            assertTrue(json.getValue("requestAccepted").jsonPrimitive.boolean)
            assertEquals("unknown", json.getValue("observedGlobalFilter").jsonPrimitive.content)
            assertTrue(json.containsKey("nextStep"))
            assertEquals(1, f.writes.size)
        }
    }

    @Test fun `denied access makes no external request`() {
        val f = Fixture().apply { access = false }
        assertFalse(f.send().success)
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.reads)
    }

    @Test fun `setter failure is unconfirmed and is not retried`() {
        val f = Fixture().apply { failure = SecurityException() }
        val result = f.send()
        assertFalse(result.success)
        assertEquals("resource:${R.string.dnd_request_unconfirmed}", result.output)
        assertEquals(1, f.writes.size)
        assertEquals(0, f.reads)
    }

    @Test fun `cancellation propagates at write and read without compensation`() {
        for (atWrite in listOf(true, false)) {
            val cancelled = CancellationException("cancelled")
            val f = Fixture().apply {
                if (atWrite) failure = cancelled else readFailure = cancelled
            }
            try {
                f.send()
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
            assertEquals(listOf(INTERRUPTION_FILTER_ALL), f.writes)
        }
    }
}
