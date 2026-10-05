package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchPolicyTest {

    @Test
    fun normalizeUrl_addsSchemeAndValidates() {
        assertEquals("https://example.com/a", WebContentActions.normalizeUrl("example.com/a"))
        assertEquals("https://example.com/", WebContentActions.normalizeUrl("https://example.com/"))
        assertNull(WebContentActions.normalizeUrl(""))
        assertNull(WebContentActions.normalizeUrl("ftp://example.com/x"))
        assertNull(WebContentActions.normalizeUrl("javascript:alert(1)"))
        assertNull(WebContentActions.normalizeUrl("https://"))
    }

    @Test
    fun blockedHost_loopbackAndPrivate() {
        listOf(
            "localhost", "LOCALHOST", "a.localhost",
            "127.0.0.1", "127.1.2.3",
            "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.255",
            "192.168.0.1", "192.168.1.1",
            "::1", "[::1]",
        ).forEach { host ->
            assertTrue("должен быть заблокирован: $host", WebContentActions.isBlockedHost(host))
        }
    }

    @Test
    fun blockedHost_publicPasses() {
        listOf(
            "example.com", "google.com",
            "8.8.8.8", "1.1.1.1",
            "172.15.0.1", "172.32.0.1",
            "192.167.0.1", "11.0.0.1",
        ).forEach { host ->
            assertFalse("должен проходить: $host", WebContentActions.isBlockedHost(host))
        }
    }
}
