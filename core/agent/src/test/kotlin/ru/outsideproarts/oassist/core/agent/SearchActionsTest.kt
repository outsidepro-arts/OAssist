package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.SearchEngine

class SearchActionsTest {

    @Test
    fun buildSearchUrl_perEngine() {
        assertEquals("https://html.duckduckgo.com/html/?q=a+b", buildSearchUrl(SearchEngine.DUCKDUCKGO, "a b"))
        assertEquals("https://yandex.ru/search/?text=a+b", buildSearchUrl(SearchEngine.YANDEX, "a b"))
        assertEquals("https://www.google.com/search?gbv=1&num=10&q=a+b", buildSearchUrl(SearchEngine.GOOGLE, "a b"))
    }

    @Test
    fun interpretSearch_success_formatsNumberedResults() {
        val html = """
            <div class="result">
              <a class="result__a" href="https://e.com/1">T1</a>
              <a class="result__snippet">S1</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://e.com/2">T2</a>
              <a class="result__snippet">S2</a>
            </div>
        """.trimIndent()

        val result = interpretSearch(SearchEngine.DUCKDUCKGO, "q", html)

        assertTrue(result.success)
        assertTrue(result.output.contains("1. T1"))
        assertTrue(result.output.contains("https://e.com/1"))
        assertTrue(result.output.contains("2. T2"))
        assertTrue(result.output.contains("web_fetch"))
    }

    @Test
    fun interpretSearch_blocked_honestError() {
        val result = interpretSearch(SearchEngine.YANDEX, "q", "<div class='smartcaptcha'>check</div>")

        assertFalse(result.success)
        assertTrue(result.output.contains("заблокировала"))
        assertTrue(result.output.contains("Яндекс"))
    }

    @Test
    fun interpretSearch_empty_honestError() {
        val result = interpretSearch(SearchEngine.BING, "q", "<html>empty</html>")

        assertFalse(result.success)
        assertTrue(result.output.contains("не вернула результатов"))
    }

    @Test
    fun format_capsResults() {
        val results = (1..20).map { SearchResult("T$it", "https://e.com/$it", "S$it") }

        val out = formatSearchResults(SearchEngine.MOJEEK, "q", results)

        assertTrue(out.contains("8. T8"))
        assertFalse(out.contains("9. T9"))
    }
}