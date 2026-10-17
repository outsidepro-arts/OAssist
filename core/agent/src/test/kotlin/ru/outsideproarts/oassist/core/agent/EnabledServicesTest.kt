package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledServicesTest {

    private val full = "ru.outsideproarts.oassist/ru.outsideproarts.oassist.a11y.OAssistAccessibilityService"
    private val short = "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService"

    @Test
    fun normalize_shortForm_expandsToFull() {
        assertEquals(full, normalizeServiceComponent(short))
    }

    @Test
    fun normalize_fullForm_unchanged() {
        assertEquals(full, normalizeServiceComponent(full))
    }

    @Test
    fun normalize_notAComponent_keepsEntry() {
        assertEquals("null", normalizeServiceComponent("null"))
        assertEquals("ru.outsideproarts.oassist/", normalizeServiceComponent("ru.outsideproarts.oassist/"))
    }

    @Test
    fun contain_matchesBothNotations() {
        assertTrue(enabledServicesContain(short, full))
        assertTrue(enabledServicesContain(full, short))
        assertTrue(enabledServicesContain("other/pkg.Cls:$short", full))
    }

    @Test
    fun contain_otherComponent_notMatched() {
        assertFalse(enabledServicesContain("com.google.android.marvin.talkback/.TalkBackService", full))
        assertFalse(enabledServicesContain("ru.outsideproarts.oassist/.a11y.OtherService", full))
    }

    @Test
    fun contain_nullOrBlank_false() {
        assertFalse(enabledServicesContain(null, full))
        assertFalse(enabledServicesContain("", full))
        assertFalse(enabledServicesContain("  ", full))
        assertFalse(enabledServicesContain(":", full))
    }

    @Test
    fun awaitReconnected_returnsProviderValueAfterReconnect() {
        var rebinds = 0
        var ready = false
        val value = runBlocking {
            awaitReconnected(200, 5, { rebinds++; ready = true }) { if (ready) "svc" else null }
        }
        assertEquals("svc", value)
        assertEquals(1, rebinds)
    }

    @Test
    fun awaitReconnected_neverConnects_returnsNull() {
        var rebinds = 0
        val value = runBlocking {
            awaitReconnected(20, 5, { rebinds++ }) { null }
        }
        assertNull(value)
        assertEquals(1, rebinds)
    }

    @Test
    fun awaitReconnected_zeroTimeout_stillRequestsReconnectWithoutWaiting() {
        var rebinds = 0
        val value = runBlocking {
            awaitReconnected(0, 5, { rebinds++ }) { "svc" }
        }
        assertNull(value)
        assertEquals(1, rebinds)
    }
}
