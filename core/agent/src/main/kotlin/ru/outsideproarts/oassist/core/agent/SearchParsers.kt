package ru.outsideproarts.oassist.core.agent

import java.net.URLDecoder
import java.net.URLEncoder
import ru.outsideproarts.oassist.core.model.SearchEngine

/** Один результат поиска (заголовок/URL/сниппет) — для контекста модели. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/** Итог разбора выдачи: результаты или честный сигнал «движок заблокировал». */
data class SearchParse(
    val results: List<SearchResult>,
    val blocked: Boolean = false,
)

/**
 * Разбор HTML-выдачи поисковиков. Свой regex-парсинг (без jsoup — как в `WebMarkdown`):
 * каждый движок со своей разметкой, часть движков может менять её — тогда результат
 * пустой и тул честно об этом говорит, ничего не выдумывая.
 */
object SearchParsers {

    private val A_TAG = Regex("""<a\s([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val CLASS = Regex("""class\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val BLOCK_MARKERS = listOf(
        "smartcaptcha", "showcaptcha", "captcha-form", "altcha",
        "detected unusual traffic", "verify you are human",
        "подтвердите, что вы не робот", "смарткапча",
    )

    fun parse(engine: SearchEngine, html: String): SearchParse {
        val results = when (engine) {
            SearchEngine.DUCKDUCKGO -> parseDdg(html)
            SearchEngine.MOJEEK -> parseMojeek(html)
            SearchEngine.BING -> parseBing(html)
            SearchEngine.ECOSIA -> parseByClasses(html, "result-title", "result-snippet", unwrapParam = "q")
            SearchEngine.GOOGLE -> parseGoogle(html)
            SearchEngine.YANDEX -> parseByClasses(html, "OrganicTitle-Link", "OrganicText")
            SearchEngine.MAILRU -> parseByClasses(html, "result__title", "result__snippet")
            SearchEngine.BRAVE -> parseByClasses(html, "heading-serp", "snippet-description")
        }
        val cleaned = results.filter { it.url.startsWith("http") }.take(12)
        val blocked = cleaned.isEmpty() && BLOCK_MARKERS.any { html.contains(it, ignoreCase = true) }
        return SearchParse(cleaned, blocked)
    }

    /** DuckDuckGo HTML: `a.result__a` + `.result__snippet`, ссылки через `/l/?uddg=`. */
    private fun parseDdg(html: String): List<SearchResult> {
        val titles = anchors(html, "result__a").map { (href, text) -> unwrapRedirect(href, "uddg") to text }
        val snippets = classTexts(html, "result__snippet")
        return zipResults(titles, snippets)
    }


    /** Mojeek: `a.title` + `p.s`. */
    private fun parseMojeek(html: String): List<SearchResult> {
        val titles = anchors(html, "title").map { (href, text) -> href to text }
        val snippets = classTexts(html, "s", tag = "p")
        return zipResults(titles, snippets)
    }

    /** Bing: блоки `li.b_algo`, первый href + текст h2, сниппет — первый `<p>`. */
    private fun parseBing(html: String): List<SearchResult> {
        val blocks = splitBlocks(html, "b_algo")
        return blocks.mapNotNull { block ->
            val anchor = anchors(block, classContains = null).firstOrNull { (href, _) -> href.startsWith("http") }
                ?: return@mapNotNull null
            val title = Regex("""<h2[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.let { htmlText(it) } ?: anchor.second
            val snippet = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.let { htmlText(it) }.orEmpty()
            SearchResult(title = title, url = anchor.first, snippet = snippet)
        }
    }

    /** Google (`gbv=1`): href `/url?q=`, заголовки в h3, сниппеты `.st`/`.VwiC3b`/`.s3v9rd`. */
    private fun parseGoogle(html: String): List<SearchResult> {
        val titles = anchors(html, classContains = null)
            .filter { (href, text) -> (href.startsWith("/url?q=") || href.startsWith("http")) && text.isNotBlank() }
            .map { (href, text) ->
                val url = if (href.startsWith("/url?q=")) unwrapRedirect(href, "q") else href
                url to text
            }
        val snippets = listOf("st", "VwiC3b", "s3v9rd", "BNeawe")
            .firstNotNullOfOrNull { cls -> classTexts(html, cls).takeIf { it.isNotEmpty() } }
            .orEmpty()
        return zipResults(titles, snippets)
    }

    /** Универсальный разбор «якорь с классом + сниппет с классом» (Ecosia/Яндекс/Mail/Brave). */
    private fun parseByClasses(
        html: String,
        titleClass: String,
        snippetClass: String,
        unwrapParam: String? = null,
    ): List<SearchResult> {
        val titles = anchors(html, titleClass).map { (href, text) ->
            val url = unwrapParam?.let { unwrapRedirect(href, it) } ?: href
            url to text
        }
        val snippets = classTexts(html, snippetClass)
        return zipResults(titles, snippets)
    }

    private fun zipResults(titles: List<Pair<String, String>>, snippets: List<String>): List<SearchResult> {
        if (titles.isEmpty()) return emptyList()
        return titles.mapIndexed { i, (url, title) ->
            SearchResult(
                title = title.ifBlank { url },
                url = url,
                snippet = snippets.getOrNull(i).orEmpty(),
            )
        }
    }

    private fun anchors(html: String, classContains: String?): List<Pair<String, String>> =
        A_TAG.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            if (classContains != null) {
                val cls = CLASS.find(attrs)?.groupValues?.get(1).orEmpty()
                if (!cls.contains(classContains)) return@mapNotNull null
            }
            val href = HREF.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val text = htmlText(m.groupValues[2])
            if (text.isBlank()) return@mapNotNull null
            href to text
        }.toList()

    private fun classTexts(html: String, classContains: String, tag: String = "\\w+"): List<String> {
        val re = Regex("""<$tag[^>]*class\s*=\s*"[^"]*$classContains[^"]*"[^>]*>([\s\S]*?)</$tag>""", RegexOption.IGNORE_CASE)
        return re.findAll(html).map { htmlText(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
    }

    private fun splitBlocks(html: String, classContains: String): List<String> {
        val re = Regex("""<(\w+)[^>]*class\s*=\s*"[^"]*$classContains[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)
        val starts = re.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexed { i, start ->
            html.substring(start, starts.getOrNull(i + 1) ?: html.length)
        }
    }

    /** Разворот редиректа (`/url?q=…`, `//duckduckgo.com/l/?uddg=…`) в прямой URL. */
    internal fun unwrapRedirect(href: String, param: String): String {
        val marker = "$param="
        val idx = href.indexOf(marker)
        if (idx < 0) return href
        val tail = href.substring(idx + marker.length).substringBefore("&")
        return decodeUrl(tail).ifBlank { href }
    }

    private fun decodeUrl(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    internal fun htmlText(raw: String): String {
        val noTags = raw.replace(Regex("""<[^>]+>"""), " ")
        return decodeEntities(noTags).replace(Regex("""\s+"""), " ").trim()
    }

    private fun decodeEntities(s: String): String {
        var out = s
        out = out.replace("&nbsp;", " ")
        out = out.replace("&amp;", "&")
        out = out.replace("&lt;", "<")
        out = out.replace("&gt;", ">")
        out = out.replace("&quot;", "\"")
        out = out.replace("&#39;", "'")
        out = out.replace("&apos;", "'")
        return out
    }
}

/** URL выдачи каждого движка. Ключей нет — только HTML-страница результатов. */
internal fun buildSearchUrl(engine: SearchEngine, query: String): String {
    val q = URLEncoder.encode(query, "UTF-8")
    return when (engine) {
        SearchEngine.DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=$q"
        SearchEngine.MOJEEK -> "https://www.mojeek.com/search?q=$q"
        SearchEngine.BING -> "https://www.bing.com/search?q=$q"
        SearchEngine.ECOSIA -> "https://www.ecosia.org/search?q=$q"
        SearchEngine.GOOGLE -> "https://www.google.com/search?gbv=1&num=10&q=$q"
        SearchEngine.YANDEX -> "https://yandex.ru/search/?text=$q"
        SearchEngine.MAILRU -> "https://go.mail.ru/search?q=$q"
        SearchEngine.BRAVE -> "https://search.brave.com/search?q=$q"
    }
}

/** Имя движка для сообщений модели (модель-сайд текст — русский, как у прочих тулов). */
internal fun engineLabel(engine: SearchEngine): String = when (engine) {
    SearchEngine.DUCKDUCKGO -> "DuckDuckGo"
    SearchEngine.YANDEX -> "Яндекс"
    SearchEngine.GOOGLE -> "Google"
    SearchEngine.BING -> "Bing"
    SearchEngine.MAILRU -> "Mail.ru"
    SearchEngine.ECOSIA -> "Ecosia"
    SearchEngine.BRAVE -> "Brave"
    SearchEngine.MOJEEK -> "Mojeek"
}