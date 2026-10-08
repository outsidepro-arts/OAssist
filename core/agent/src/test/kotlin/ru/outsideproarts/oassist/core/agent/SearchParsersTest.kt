package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.SearchEngine

class SearchParsersTest {

    @Test
    fun duckduckgo_parsesResultsAndUnwrapsRedirects() {
        val html = """
            <div class="result">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&amp;rut=x">Example title</a>
              <a class="result__snippet">Useful snippet</a>
            </div>
        """.trimIndent()

        val parsed = SearchParsers.parse(SearchEngine.DUCKDUCKGO, html)

        assertFalse(parsed.blocked)
        assertEquals(1, parsed.results.size)
        assertEquals("https://example.com/page", parsed.results[0].url)
        assertEquals("Example title", parsed.results[0].title)
        assertEquals("Useful snippet", parsed.results[0].snippet)
    }

    @Test
    fun mojeek_parsesTitleAndSnippet() {
        val html = """
            <li><h2><a class="title" href="https://mojeek.example/one">First</a></h2>
            <p class="s">Snippet one</p></li>
        """.trimIndent()

        val parsed = SearchParsers.parse(SearchEngine.MOJEEK, html)

        assertEquals(listOf("https://mojeek.example/one"), parsed.results.map { it.url })
        assertEquals("Snippet one", parsed.results[0].snippet)
    }

    @Test
    fun bing_parsesBlocks() {
        val html = """
            <li class="b_algo"><h2><a href="https://bing.example/two">Second</a></h2>
            <p>Snippet two</p></li>
        """.trimIndent()

        val parsed = SearchParsers.parse(SearchEngine.BING, html)

        assertEquals(1, parsed.results.size)
        assertEquals("Second", parsed.results[0].title)
        assertEquals("Snippet two", parsed.results[0].snippet)
    }

    @Test
    fun google_unwrapsUrlRedirect() {
        val html = """
            <a href="/url?q=https%3A%2F%2Fg.example%2Fthree&amp;sa=U"><h3>Third</h3></a>
            <div class="st">Snippet three</div>
        """.trimIndent()

        val parsed = SearchParsers.parse(SearchEngine.GOOGLE, html)

        assertEquals("https://g.example/three", parsed.results[0].url)
        assertEquals("Third", parsed.results[0].title)
    }

    @Test
    fun yandex_parsesOrganic() {
        val html = """
            <li class="serp-item"><a class="OrganicTitle-Link" href="https://yandex.example/four">Fourth</a>
            <div class="OrganicText">Snippet four</div></li>
        """.trimIndent()

        val parsed = SearchParsers.parse(SearchEngine.YANDEX, html)

        assertEquals("Fourth", parsed.results[0].title)
        assertEquals("Snippet four", parsed.results[0].snippet)
    }

    @Test
    fun mailru_and_brave_and_ecosia_parseTheirClasses() {
        val mail = """<a class="result__title" href="https://mail.example/f">Mail</a><div class="result__snippet">Sn</div>"""
        val brave = """<a class="heading-serpresult" href="https://brave.example/b">Brave</a><div class="snippet-description">Sn</div>"""
        val ecosia = """<a class="result-title" href="https://ecosia.example/e">Ecosia</a><p class="result-snippet">Sn</p>"""

        assertEquals("Mail", SearchParsers.parse(SearchEngine.MAILRU, mail).results[0].title)
        assertEquals("Brave", SearchParsers.parse(SearchEngine.BRAVE, brave).results[0].title)
        assertEquals("Ecosia", SearchParsers.parse(SearchEngine.ECOSIA, ecosia).results[0].title)
    }

    @Test
    fun emptyResults_notBlocked() {
        val parsed = SearchParsers.parse(SearchEngine.DUCKDUCKGO, "<html><body>nothing</body></html>")

        assertFalse(parsed.blocked)
        assertTrue(parsed.results.isEmpty())
    }

    @Test
    fun captchaPage_marksBlocked() {
        val html = "<html><body><div id='captcha-form'>SmartCaptcha</div></body></html>"

        val parsed = SearchParsers.parse(SearchEngine.YANDEX, html)

        assertTrue(parsed.blocked)
        assertTrue(parsed.results.isEmpty())
    }

    @Test
    fun resultsWithCaptchaWordInPage_notBlocked() {
        val html = """
            <a class="result__a" href="https://ok.example/x">Ok</a>
            <a class="result__snippet">captcha mention but results present</a>
        """.trimIndent()

        val parsed = SearchParsers.parse(SearchEngine.DUCKDUCKGO, html)

        assertFalse(parsed.blocked)
    }

    @Test
    fun htmlText_stripsTagsAndEntities() {
        assertEquals("a & b", SearchParsers.htmlText("a &amp; <b>b</b>"))
    }
}