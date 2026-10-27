package ru.outsideproarts.oassist.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCompletenessTest {
    private fun client(html: String, htmlType: String, markdown: String? = null): HttpClient = HttpClient(
        MockEngine { request ->
            val isMarkdown = request.url.encodedPath.endsWith(".md")
            val body = if (isMarkdown) markdown ?: "<html>no alternative</html>" else html
            val contentType = if (isMarkdown && markdown != null) "text/markdown" else htmlType
            respond(body, HttpStatusCode.OK, headersOf("Content-Type" to listOf(contentType)))
        },
    )

    @Test
    fun sourceBoundaryChecksActualAdditionalByte() = runBlocking {
        val cap = WebContentActions.MAX_BODY_BYTES
        for ((size, expected) in listOf(cap - 1 to false, cap to false, cap + 1 to true)) {
            client("x".repeat(size), "text/plain").use { http ->
                val raw = WebContentActions(http).fetchStatic("https://example.com/page").getOrThrow()
                assertEquals(expected, raw.bodyTruncated)
                assertEquals(minOf(size, cap), raw.body.length)
            }
        }
    }

    @Test
    fun articleRetainsSourceTruncationWhenOutputFits() = runBlocking {
        val article = "<html><head><title>Article</title></head><body><article><h1>Article</h1><p>" +
            "Useful article text. ".repeat(80) + "</p></article></body></html>"
        val html = article + " ".repeat(WebContentActions.MAX_BODY_BYTES)
        client(html, "text/html").use { http ->
            val page = WebFetcher(WebContentActions(http), null).fetch(
                "https://example.com/page", maxChars = 50_000, renderMode = RenderMode.NEVER,
            ).getOrThrow()
            assertTrue(page.markdown.contains("Useful article text"))
            assertTrue(page.truncated)
            assertTrue(page.markdown.length < 50_000)
        }
    }

    @Test
    fun plainHtmlFallbackRetainsSourceTruncation() = runBlocking {
        val html = "<html><body>short</body></html>" + " ".repeat(WebContentActions.MAX_BODY_BYTES)
        client(html, "text/html").use { http ->
            val page = WebFetcher(WebContentActions(http), null).fetch(
                "https://example.com/page", maxChars = 50_000, renderMode = RenderMode.NEVER,
            ).getOrThrow()
            assertTrue(page.markdown.contains("short"))
            assertTrue(page.truncated)
        }
    }

    @Test
    fun markdownAlternativeUsesItsOwnSourceCompleteness() = runBlocking {
        val html = "<html><body>short</body></html>" + " ".repeat(WebContentActions.MAX_BODY_BYTES)
        val markdown = "Alternative complete content. ".repeat(12)
        client(html, "text/html", markdown).use { http ->
            val page = WebFetcher(WebContentActions(http), null).fetch(
                "https://example.com/page", maxChars = 50_000, renderMode = RenderMode.NEVER,
            ).getOrThrow()
            assertEquals(markdown, page.markdown)
            assertFalse(page.truncated)
            assertTrue(page.finalUrl.endsWith(".md"))
        }
        client("<html></html>", "text/html", "m".repeat(WebContentActions.MAX_BODY_BYTES + 1)).use { http ->
            val alternative = WebContentActions(http).fetchMarkdownVariant("https://example.com/page").getOrThrow()
            assertTrue(alternative.bodyTruncated)
        }
    }

    @Test
    fun toolReportsModeAndOutputTruncation() = runBlocking {
        client("x".repeat(1000), "text/plain").use { http ->
            val tool = WebFetchTool(WebFetcher(WebContentActions(http), null))
            val result = tool.execute(buildJsonObject {
                put("url", "https://example.com/page")
                put("mode", "gist")
                put("max_chars", 500)
                put("render", "never")
            })
            assertTrue(result.success)
            assertTrue(result.output.contains("gist (краткий режим)"))
            assertTrue(result.output.contains("обнаружено усечение"))
        }
        client("short", "text/plain").use { http ->
            val result = WebFetchTool(WebFetcher(WebContentActions(http), null)).execute(buildJsonObject {
                put("url", "https://example.com/page")
            })
            assertTrue(result.output.contains("усечение по лимиту не обнаружено"))
        }
    }

    @Test
    fun renderedDomBoundaryPreservesClipping() {
        val cap = WebRenderActions.MAX_RENDER_CHARS
        val exact = cappedRenderedPage("Title", "https://example.com", "a".repeat(cap))
        val clipped = cappedRenderedPage("Title", "https://example.com", "a".repeat(cap + 1))
        assertFalse(exact.bodyTruncated)
        assertTrue(clipped.bodyTruncated)
        assertEquals(cap, clipped.html.length)
    }
}
