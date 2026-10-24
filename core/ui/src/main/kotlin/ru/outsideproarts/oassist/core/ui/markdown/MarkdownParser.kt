package ru.outsideproarts.oassist.core.ui.markdown

/**
 * Блок верхнего уровня Markdown. Парсер покрывает разметку, которую реально
 * отдают модели в ответах: заголовки, абзацы, списки, код, цитаты, разделители.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val content: List<MdInline>) : MdBlock
    data class Paragraph(val content: List<MdInline>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Quote(val content: List<MdInline>) : MdBlock
    data class BulletList(val items: List<List<MdInline>>) : MdBlock
    data class OrderedList(val items: List<Item>) : MdBlock {
        data class Item(val number: Int, val content: List<MdInline>)
    }
    object Divider : MdBlock
}

/** Инлайн-разметка внутри блока. */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Strong(val content: List<MdInline>) : MdInline
    data class Emphasis(val content: List<MdInline>) : MdInline
    data class Strikethrough(val content: List<MdInline>) : MdInline
    data class CodeSpan(val text: String) : MdInline
    data class Link(val label: List<MdInline>, val url: String) : MdInline
    data class Image(val alt: String, val url: String) : MdInline
}

/**
 * Мини-Markdown парсер (pure Kotlin, без зависимостей): блочная структура
 * построчно, инлайны — однопроходный сканер с рекурсией по вложенным
 * маркерам. Сознательно терпит «дикий» текст моделей: непарные маркеры,
 * underscore в идентификаторах и битые ссылки остаются литералом.
 */
object MarkdownParser {

    /** Потолок вложенности инлайнов — защита от патологических входов. */
    private const val MAX_DEPTH = 12

    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val FENCE = Regex("^```\\s*(\\S*)\\s*$")
    private val DIVIDER = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")

    fun parse(source: String): List<MdBlock> {
        if (source.isEmpty()) return emptyList()
        val lines = source.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            val fence = FENCE.matchEntire(trimmed)
            if (fence != null) {
                val lang = fence.groupValues[1].ifBlank { null }
                val code = StringBuilder()
                var j = i + 1
                while (j < lines.size && !lines[j].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[j])
                    j++
                }
                blocks += MdBlock.Code(lang, code.toString())
                i = if (j < lines.size) j + 1 else j
                continue
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                val level = trimmed.takeWhile { it == '#' }.length
                val content = heading.groupValues[1].trim().trimEnd('#').trim()
                blocks += MdBlock.Heading(level, parseInlines(content))
                i++
                continue
            }

            if (DIVIDER.matches(trimmed)) {
                blocks += MdBlock.Divider
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                var j = i
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (!t.startsWith(">")) break
                    quoteLines += t.removePrefix(">").removePrefix(" ").trim()
                    j++
                }
                blocks += MdBlock.Quote(parseInlines(quoteLines.joinToString("\n")))
                i = j
                continue
            }

            if (BULLET.matches(line)) {
                val items = mutableListOf<List<MdInline>>()
                var j = i
                while (j < lines.size) {
                    val m = BULLET.matchEntire(lines[j]) ?: break
                    items += parseInlines(m.groupValues[1].trim())
                    j++
                }
                blocks += MdBlock.BulletList(items)
                i = j
                continue
            }

            if (ORDERED.matches(line)) {
                val items = mutableListOf<MdBlock.OrderedList.Item>()
                var j = i
                while (j < lines.size) {
                    val m = ORDERED.matchEntire(lines[j]) ?: break
                    items += MdBlock.OrderedList.Item(m.groupValues[1].toInt(), parseInlines(m.groupValues[2].trim()))
                    j++
                }
                blocks += MdBlock.OrderedList(items)
                i = j
                continue
            }

            // Абзац: идёт до пустой строки или до начала структурного элемента.
            val para = mutableListOf<String>()
            var j = i
            while (j < lines.size) {
                val next = lines[j]
                val nextTrimmed = next.trim()
                if (nextTrimmed.isEmpty()) break
                if (j > i && startsBlock(next, nextTrimmed)) break
                para += nextTrimmed
                j++
            }
            blocks += MdBlock.Paragraph(parseInlines(para.joinToString("\n")))
            i = j
        }
        return blocks
    }

    private fun startsBlock(line: String, trimmed: String): Boolean =
        FENCE.matches(trimmed) ||
            HEADING.matches(trimmed) ||
            DIVIDER.matches(trimmed) ||
            trimmed.startsWith(">") ||
            BULLET.matches(line) ||
            ORDERED.matches(line)

    /** Однопроходный парсер инлайнов: литералы, code span, ссылки, картинки, акценты. */
    fun parseInlines(text: String, depth: Int = 0): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        if (depth > MAX_DEPTH) return listOf(MdInline.Text(text))
        val out = mutableListOf<MdInline>()
        val literal = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    literal.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flushLiteral(literal, out)
                        out += MdInline.CodeSpan(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    val parsed = parseLink(text, i + 1)
                    if (parsed != null) {
                        flushLiteral(literal, out)
                        out += MdInline.Image(parsed.first, parsed.second)
                        i = parsed.third
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                c == '[' -> {
                    val parsed = parseLink(text, i)
                    if (parsed != null) {
                        flushLiteral(literal, out)
                        out += MdInline.Link(parseInlines(parsed.first, depth + 1), parsed.second)
                        i = parsed.third
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                c == '~' && i + 1 < text.length && text[i + 1] == '~' -> {
                    val end = findDouble(text, i + 2, '~')
                    val content = if (end > i + 2) text.substring(i + 2, end) else null
                    if (content != null && content.isNotBlank() && !content.endsWith('~')) {
                        flushLiteral(literal, out)
                        out += MdInline.Strikethrough(parseInlines(content, depth + 1))
                        i = end + 2
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                c == '*' || c == '_' -> {
                    val pair = tryDelimiter(text, i, c, depth)
                    if (pair != null) {
                        flushLiteral(literal, out)
                        out += pair.first
                        i = pair.second
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flushLiteral(literal, out)
        return out
    }

    private val ESCAPABLE = "*_`[]~!\\"

    private fun flushLiteral(sb: StringBuilder, out: MutableList<MdInline>) {
        if (sb.isNotEmpty()) {
            out += MdInline.Text(sb.toString())
            sb.setLength(0)
        }
    }

    /**
     * `**x**`/`__x__` — двойной маркер, `*x*`/`_x_` — одинарный.
     * Underscore внутри слова (`snake_case`) литералом — иначе идентификаторы
     * из ответов модели превращаются в курсив.
     */
    private fun tryDelimiter(
        text: String,
        start: Int,
        marker: Char,
        depth: Int,
    ): Pair<MdInline, Int>? {
        val double = start + 1 < text.length && text[start + 1] == marker
        if (double) {
            val contentStart = start + 2
            val end = findDouble(text, contentStart, marker)
            if (end <= contentStart) return null
            val content = text.substring(contentStart, end)
            if (content.isBlank()) return null
            if (marker == '_' && (!underscoreOpen(text, start) || !underscoreClose(text, end + 2))) return null
            return MdInline.Strong(parseInlines(content, depth + 1)) to end + 2
        }

        if (marker == '_' && !underscoreOpen(text, start)) return null
        val contentStart = start + 1
        var i = contentStart
        while (i < text.length) {
            if (text[i] == marker) {
                if ((i + 1 < text.length && text[i + 1] == marker) ||
                    (i > 0 && text[i - 1] == marker)
                ) {
                    i++
                    continue
                }
                if (i == contentStart) return null
                val content = text.substring(contentStart, i)
                if (content.isBlank() || content.endsWith(' ')) return null
                if (marker == '_' && !underscoreClose(text, i + 1)) return null
                return MdInline.Emphasis(parseInlines(content, depth + 1)) to i + 1
            }
            i++
        }
        return null
    }

    private fun findDouble(text: String, from: Int, marker: Char): Int {
        var i = from
        while (i + 1 < text.length) {
            if (text[i] == marker && text[i + 1] == marker) return i
            i++
        }
        return -1
    }

    /** `_` — курсив только на границе слова: до открытия и после закрытия нет буквы/цифры. */
    private fun underscoreOpen(text: String, openAt: Int): Boolean {
        val before = if (openAt > 0) text[openAt - 1] else null
        return before == null || !before.isLetterOrDigit()
    }

    private fun underscoreClose(text: String, closeAt: Int): Boolean {
        val after = if (closeAt < text.length) text[closeAt] else null
        return after == null || !after.isLetterOrDigit()
    }

    /** Ссылка/картинка: `[label](url)`. Возвращает (label, url, индекс после `)`). */
    private fun parseLink(text: String, at: Int): Triple<String, String, Int>? {
        if (at >= text.length || text[at] != '[') return null
        val closeBracket = text.indexOf(']', at + 1)
        if (closeBracket < 0 || closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = text.indexOf(')', closeBracket + 2)
        if (closeParen < 0) return null
        val label = text.substring(at + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen).trim().substringBefore(' ')
        if (label.isBlank() || url.isBlank()) return null
        return Triple(label, url, closeParen + 1)
    }
}
