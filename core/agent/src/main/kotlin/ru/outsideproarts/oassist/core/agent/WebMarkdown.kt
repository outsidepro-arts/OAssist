package ru.outsideproarts.oassist.core.agent

import java.net.URI

/**
 * Извлечение статьи и конвертация HTML→Markdown без внешних зависимостей.
 * Причина: Maven Central блокируется прокси (403), JitPack отдаёт 404/purge
 * для свежих версий — тащить jsoup неоткуда. Поэтому: regex-чистка,
 * семантический скоринг, собственный unescape. На корректной разметке качество
 * сопоставимо с DOM-парсером; на битой — graceful degradation.
 */

/** Статья, извлечённая собственным скорингом (семантика + плотность текста). */
data class ExtractedArticle(
    val title: String?,
    val byline: String?,
    val excerpt: String?,
    /** Очищенный HTML фрагмент статьи. */
    val contentHtml: String,
)

/** Результат конвертации в Markdown с признаком обрезки. */
data class MarkdownResult(
    val markdown: String,
    val truncated: Boolean,
    val totalChars: Int,
)

/**
 * Извлечь статью из HTML: семантические контейнеры (`article`/`main`/`role=main`) —
 * крупнейший по тексту; иначе body без навигации/подвалов/форм.
 * `null` — текста слишком мало (тонкая страница/каркас SPA): вызывающий решает
 * (markdown-вариант, WebView-рендер, plain-фолбэк).
 */
fun extractArticle(url: String, html: String): ExtractedArticle? {
    if (html.isBlank()) return null
    return runCatching {
        val title = extractTagText(html, "title")
        val noScripts = stripBlocks(html, listOf("script", "style", "noscript", "template", "svg"))
        val semantic = pickLargest(noScripts, listOf("article", "main"))
            ?: pickByRoleMain(noScripts)
        val root = if (semantic != null && plainLength(semantic) >= MIN_ARTICLE_CHARS) {
            semantic
        } else {
            var body = innerOf(noScripts, "body") ?: noScripts
            body = stripBlocks(body, listOf("nav", "footer", "aside", "header", "form"))
            body
        }
        if (plainLength(root) < MIN_ARTICLE_CHARS) return null
        ExtractedArticle(
            title = title,
            byline = null,
            excerpt = firstLongParagraph(root),
            contentHtml = root,
        )
    }.getOrNull()
}

/** Полный Markdown статьи с капом. */
fun articleToMarkdown(
    title: String?,
    contentHtml: String,
    baseUrl: String,
    maxChars: Int,
): MarkdownResult {
    val sb = StringBuilder()
    title?.takeIf { it.isNotBlank() }?.let { sb.append("# ").appendLine(it).appendLine() }
    sb.append(htmlFragmentToMarkdown(contentHtml, baseUrl, maxChars))
    return cap(sb, maxChars)
}

/**
 * Дешёвая разведка: заголовок + excerpt + структура (h1–h3) + ссылки с URL.
 * Агент решает, читать ли целиком, не тратя контекст.
 */
fun articleGist(
    title: String?,
    excerpt: String?,
    contentHtml: String,
    baseUrl: String,
    maxChars: Int,
): MarkdownResult {
    val sb = StringBuilder()
    title?.takeIf { it.isNotBlank() }?.let { sb.append("# ").appendLine(it).appendLine() }
    excerpt?.takeIf { it.isNotBlank() }?.let { sb.appendLine(it).appendLine() }
    val headings = Regex("<h([1-3])[^>]*>(.*?)</h\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(stripBlocks(contentHtml, listOf("script", "style")))
        .map { (it.groupValues[1].toIntOrNull() ?: 2) to stripTags(it.groupValues[2]) }
        .filter { it.second.isNotBlank() }
        .take(MAX_GIST_HEADINGS)
        .toList()
    if (headings.isNotEmpty()) {
        sb.appendLine("## Структура")
        headings.forEach { (level, text) ->
            sb.append("  ".repeat((level - 1).coerceAtLeast(0))).append("- ").appendLine(text)
        }
        sb.appendLine()
    }
    val links = extractLinks(contentHtml, baseUrl)
        .filter { (text, href) -> text.isNotBlank() && href != baseUrl }
        .distinctBy { it.second }
        .take(MAX_GIST_LINKS)
    if (links.isNotEmpty()) {
        sb.appendLine("## Ссылки")
        links.forEach { (text, href) -> sb.append("- ").append(text).append(" → ").appendLine(href) }
    }
    return cap(sb, maxChars)
}

/** Сырой HTML → плоский текст (фолбэк, когда извлечь статью не вышло). */
fun htmlToPlainText(html: String, baseUrl: String, maxChars: Int): MarkdownResult {
    val clean = stripBlocks(html, listOf("script", "style", "noscript", "template", "svg"))
    val sb = StringBuilder()
    extractTagText(clean, "title")?.let { sb.append("# ").appendLine(it).appendLine() }
    sb.append(htmlFragmentToMarkdown(innerOf(clean, "body") ?: clean, baseUrl, maxChars))
    return cap(sb, maxChars)
}

// --- Внутренняя кухня: regex-парсинг ---

private fun stripBlocks(html: String, tags: List<String>): String {
    var out = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
    tags.forEach { tag ->
        out = out.replace(
            Regex("<$tag(\\s[^>]*)?>[\\s\\S]*?</$tag\\s*>", RegexOption.IGNORE_CASE),
            "",
        )
        // Самозакрывающиеся/одиночные остатки
        out = out.replace(Regex("</?$tag(\\s[^>]*)?/?>", RegexOption.IGNORE_CASE), "")
    }
    return out
}

private fun innerOf(html: String, tag: String): String? {
    val m = Regex(
        "<$tag(\\s[^>]*)?>([\\s\\S]*)</$tag\\s*>",
        RegexOption.IGNORE_CASE,
    ).find(html) ?: return null
    return m.groupValues[2]
}

private fun extractTagText(html: String, tag: String): String? {
    val inner = innerOf(html, tag) ?: return null
    return unescape(stripTags(inner)).normalizeSpace().takeIf { it.isNotBlank() }
}

/** Крупнейший по плоскому тексту из перечисленных контейнеров. */
private fun pickLargest(html: String, tags: List<String>): String? {
    var best: String? = null
    var bestLen = 0
    tags.forEach { tag ->
        Regex(
            "<$tag(\\s[^>]*)?>([\\s\\S]*)</$tag\\s*>",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { m ->
            val len = plainLength(m.groupValues[2])
            if (len > bestLen) {
                bestLen = len
                best = m.groupValues[2]
            }
        }
    }
    return best
}

private fun pickByRoleMain(html: String): String? {
    val m = Regex(
        "<(div|section)((?:\\s[^>]*)?\\srole\\s*=\\s*[\"']main[\"'][^>]*)?>([\\s\\S]*)</\\1\\s*>",
        RegexOption.IGNORE_CASE,
    ).find(html) ?: return null
    return m.groupValues[3]
}

private fun firstLongParagraph(html: String): String? {
    return Regex("<p(\\s[^>]*)?>([\\s\\S]*?)</p\\s*>", RegexOption.IGNORE_CASE)
        .findAll(html)
        .map { unescape(stripTags(it.groupValues[2])).normalizeSpace() }
        .firstOrNull { it.length > MIN_EXCERPT_CHARS }
}

private fun plainLength(html: String): Int =
    unescape(stripTags(html)).normalizeSpace().length

private fun stripTags(html: String): String =
    html.replace(Regex("<[^>]+>"), " ")

private fun extractLinks(html: String, baseUrl: String): List<Pair<String, String>> {
    val base = runCatching { URI(baseUrl) }.getOrNull()
    return Regex(
        "<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a\\s*>",
        RegexOption.IGNORE_CASE,
    ).findAll(html).mapNotNull { m ->
        val rawHref = m.groupValues[1].trim()
        if (rawHref.isBlank() || rawHref.startsWith("javascript:", ignoreCase = true)) return@mapNotNull null
        val abs = if (base != null) {
            runCatching { base.resolve(rawHref).toString() }.getOrNull() ?: rawHref
        } else rawHref
        val text = unescape(stripTags(m.groupValues[2])).normalizeSpace()
        if (text.isBlank()) return@mapNotNull null
        text to abs
    }.toList()
}

private fun htmlFragmentToMarkdown(html: String, baseUrl: String, maxChars: Int): String {
    // Скрипты/стили режем здесь же: эмиттер никогда не должен их пропускать,
    // независимо от того, чистил ли вызывающий фрагмент заранее.
    var out = stripBlocks(html, listOf("script", "style", "noscript", "template", "svg"))
    // Ссылки и картинки — в Markdown ДО общей чистки тегов (точная замена).
    out = Regex(
        "<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a\\s*>",
        RegexOption.IGNORE_CASE,
    ).replace(out) { m ->
        val href = resolveUrl(baseUrl, m.groupValues[1].trim())
        val text = unescape(stripTags(m.groupValues[2])).normalizeSpace()
        when {
            text.isBlank() -> ""
            href == null -> text
            else -> "[$text]($href)"
        }
    }
    out = Regex("<img\\s[^>]*>", RegexOption.IGNORE_CASE).replace(out) { m ->
        val alt = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(m.value)?.groupValues[1]
        if (!alt.isNullOrBlank()) "[изображение: ${unescape(alt).normalizeSpace()}]" else ""
    }
    // Блоки → переносы до чистки инлайна
    out = out.replace(Regex("</(h[1-6]|p|div|section|article|li|tr|table|ul|ol|blockquote|pre)\\s*>", RegexOption.IGNORE_CASE), "\n")
    out = out.replace(Regex("</(th|td)\\s*>", RegexOption.IGNORE_CASE), " | ")
    out = out.replace(Regex("<(br|hr)(\\s[^>]*)?/?>", RegexOption.IGNORE_CASE), "\n")
    out = out.replace(Regex("<tr(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "\n")
    out = out.replace(Regex("<li(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "\n- ")
    // Заголовки → Markdown
    for (level in 6 downTo 1) {
        val prefix = "#".repeat(level.coerceAtMost(3)) + " "
        out = out.replace(Regex("<h$level(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "\n$prefix")
    }
    // Код
    out = out.replace(Regex("<pre(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "\n```\n")
        .replace(Regex("</pre\\s*>", RegexOption.IGNORE_CASE), "\n```\n")
    out = stripTags(out)
    out = unescape(out)
    // Таблицы уже сплющены в строки; схлопнуть пробелы построчно
    out = out.lines()
        .map { it.normalizeSpace() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
    return out.take(maxChars + HEADROOM)
}

private fun resolveUrl(baseUrl: String, href: String): String? {
    if (href.isBlank() || href.startsWith("javascript:", ignoreCase = true)) return null
    return runCatching {
        URI(baseUrl).resolve(href).toString()
    }.getOrNull() ?: href.takeIf { it.contains("://") }
}

private fun cap(sb: StringBuilder, maxChars: Int): MarkdownResult {
    val total = sb.length
    if (total <= maxChars) return MarkdownResult(sb.toString().trim(), false, total)
    val cut = sb.substring(0, maxChars).trimEnd() + "\n\n[обрезано, всего $total символов]"
    return MarkdownResult(cut, true, total)
}

private fun String.normalizeSpace(): String = trim().replace(Regex("\\s+"), " ")

private fun unescape(s: String): String {
    var out = s
    NAMED_ENTITIES.forEach { (entity, char) -> out = out.replace(entity, char) }
    out = Regex("&#(\\d+);").replace(out) {
        it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value
    }
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) {
        it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
    }
    return out
}

private val NAMED_ENTITIES = mapOf(
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&nbsp;" to " ",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&laquo;" to "«",
    "&raquo;" to "»",
    "&hellip;" to "…",
    "&copy;" to "©",
)

private const val HEADROOM = 512
private const val MAX_GIST_HEADINGS = 30
private const val MAX_GIST_LINKS = 40
private const val MIN_ARTICLE_CHARS = 200
private const val MIN_EXCERPT_CHARS = 80
