package ru.outsideproarts.oassist.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchPolicyTest {

    @Test
    fun alwaysRejectsPrivateAddressBeforeRendererOrHttp() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("unexpected") })
        try {
            val result = WebFetcher(WebContentActions(client), null)
                .fetch("https://127.0.0.1/", renderMode = RenderMode.ALWAYS)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertEquals(0, calls)
        } finally { client.close() }
    }

    @Test
    fun staticRedirectRejectsPrivateBeforeNextRequest() = runBlocking {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://10.0.0.1/private"))
        }) { expectSuccess = true }
        try {
            val result = WebFetcher(WebContentActions(client), null)
                .fetch("https://example.com/start", renderMode = RenderMode.NEVER)
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertEquals(listOf("https://example.com/start"), urls)
        } finally { client.close() }
    }

    @Test
    fun publicRelativeRedirectReachesFinalPage() = runBlocking {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            if (request.url.encodedPath == "/start") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/page"))
            } else {
                respond("public page", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
        }) { expectSuccess = true }
        try {
            val page = WebFetcher(WebContentActions(client), null)
                .fetch("https://example.com/start", renderMode = RenderMode.NEVER).getOrThrow()
            assertEquals("https://example.com/page", page.finalUrl)
            assertEquals("public page", page.markdown)
            assertEquals(listOf("https://example.com/start", "https://example.com/page"), urls)
        } finally { client.close() }
    }

    @Test
    fun markdownRedirectRejectsPrivateBeforeNextRequest() = runBlocking {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://192.168.1.1/secret"))
        })
        try {
            val result = WebContentActions(client).fetchMarkdownVariant("https://example.com/page")
            assertTrue(result.exceptionOrNull() is SecurityException)
            assertEquals(listOf("https://example.com/page.md"), urls)
        } finally { client.close() }
    }

    @Test
    fun redirectLoopIsBounded() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/loop"))
        }) { expectSuccess = true }
        try {
            val result = WebContentActions(client).fetchStatic("https://example.com/loop")
            assertTrue(result.isFailure)
            assertEquals(20, calls)
        } finally { client.close() }
    }

    @Test
    fun httpsRedirectDoesNotDowngradeToHttp() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://example.com/page"))
        }) { expectSuccess = true }
        try {
            assertTrue(WebContentActions(client).fetchStatic("https://example.com/start").isFailure)
            assertEquals(1, calls)
        } finally { client.close() }
    }

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
