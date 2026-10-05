package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMarkdownTest {

    private val articleHtml = """
        <article>
            <h1>Заголовок</h1>
            <p>Первый абзац с <a href="/page2">ссылкой</a> и <a href="javascript:void(0)">пустышкой</a>.</p>
            <h2>Раздел</h2>
            <ul><li>Раз</li><li>Два</li></ul>
            <pre><code>val x = 1</code></pre>
            <table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>
            <img src="pic.png" alt="Кот" />
            <script>alert(1)</script>
        </article>
    """.trimIndent()

    @Test
    fun full_markdownStructure() {
        val md = articleToMarkdown("Страница", articleHtml, "https://example.com/a", 50_000)
        assertFalse(md.truncated)
        val text = md.markdown
        assertTrue(text.contains("# Страница"))
        assertTrue(text.contains("# Заголовок"))
        assertTrue(text.contains("## Раздел"))
        assertTrue(text.contains("- Раз"))
        assertTrue(text.contains("[ссылкой](https://example.com/page2)"))
        assertTrue(text.contains("пустышкой") && !text.contains("javascript:"))
        assertTrue(text.contains("```"))
        assertTrue(text.contains("A | B"))
        assertTrue(text.contains("[изображение: Кот]"))
        assertFalse(text.contains("alert(1)"))
    }

    @Test
    fun full_capTruncatesWithNote() {
        val md = articleToMarkdown(null, articleHtml, "https://example.com/", 50)
        assertTrue(md.truncated)
        assertTrue(md.markdown.contains("[обрезано, всего"))
        assertTrue(md.totalChars > 50)
    }

    @Test
    fun gist_headingsAndLinks() {
        val md = articleGist("Страница", "Кратко о главном", articleHtml, "https://example.com/a", 50_000)
        assertFalse(md.truncated)
        val text = md.markdown
        assertTrue(text.contains("# Страница"))
        assertTrue(text.contains("Кратко о главном"))
        assertTrue(text.contains("## Структура"))
        assertTrue(text.contains("## Ссылки"))
        assertTrue(text.contains("https://example.com/page2"))
    }

    @Test
    fun plainFallback_stripsScripts() {
        val html = "<html><head><title>T</title><script>var x=1</script></head><body><p>Текст</p></body></html>"
        val md = htmlToPlainText(html, "https://example.com/", 50_000)
        assertTrue(md.markdown.contains("Текст"))
        assertFalse(md.markdown.contains("var x=1"))
    }

    @Test
    fun extract_picksArticleOverNav() {
        val html = """
            <html><head><title>Новость дня</title></head>
            <body><nav>Меню</nav>
            <article><h1>Новость дня</h1>
            <p>Первый абзац новости, достаточно длинный чтобы алгоритм посчитал его содержимым статьи, а не мусором.</p>
            <p>Второй абзац новости продолжает повествование и добавляет деталей происходящего сегодня.</p>
            </article></body></html>
        """.trimIndent()
        val article = extractArticle("https://example.com/news", html)
        assertNotNull(article)
        assertEquals("Новость дня", article!!.title)
        assertTrue(article.contentHtml.contains("Первый абзац"))
        assertFalse(article.contentHtml.contains("Меню"))
    }

    @Test
    fun extract_garbageReturnsNull() {
        assertNull(extractArticle("https://example.com/", ""))
        assertNull(extractArticle("not a url", "<p>hi</p>"))
    }

    @Test
    fun decodeJsString_unquotes() {
        assertEquals("Привет", decodeJsString("\"Привет\""))
        assertEquals("a\nb", decodeJsString("\"a\\nb\""))
        assertNull(decodeJsString("null"))
        assertNull(decodeJsString(null))
    }
}
