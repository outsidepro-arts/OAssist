package ru.outsideproarts.oassist.core.speech

/**
 * Снятие Markdown-атрибутов перед синтезом речи: на входе — предложение из
 * ответа модели, на выходе — чистый текст без `**`, `#`, ссылок-разметки и т.п.
 * Текст ссылок и alt-картинок сохраняются (озвучиваются они, а не URL).
 *
 * Экранированные маркеры (`\*`) не разметка: на них стоят негативные
 * lookbehind'ы, обратное снятие экранирования идёт последним шагом.
 */
object MarkdownPlain {

    private val ESCAPES = Regex("""\\([*_`\[\]~#!\\-])""")
    private val IMAGE = Regex("""(?<!\\)!\[([^\]]*)\]\([^)]*\)""")
    private val LINK = Regex("""(?<!\\)\[([^\]]+)\]\([^)]*\)""")
    private val CODE_SPAN = Regex("""(?<!\\)`([^`]+)`""")
    private val STRONG = Regex("""(?<!\\)\*\*(.+?)\*\*""")
    private val UNDERSCORE_STRONG = Regex("""(?<!\\)__(.+?)__""")
    private val STRIKE = Regex("""(?<!\\)~~(.+?)~~""")
    private val EMPHASIS = Regex("""(?<!\\)(?<!\*)\*([^*\n]+)\*(?!\*)""")
    private val UNDERSCORE_EMPH = Regex(
        """(?<!\\)(?<![0-9A-Za-zА-Яа-яЁё])_([^_\s][^_]*?)_(?![0-9A-Za-zА-Яа-яЁё])""",
    )
    private val HEADING_MARK = Regex("""^#{1,6}\s+""")
    private val HEADING_TAIL = Regex("""\s+#+\s*$""")
    private val QUOTE_MARK = Regex("""^\s*>\s?""")
    private val BULLET_MARK = Regex("""^\s*[-*+]\s+""")
    private val ORDERED_MARK = Regex("""^\s*\d{1,9}[.)]\s+""")
    private val DIVIDER_LINE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
    private val FENCE_LINE = Regex("""^\s*```""")

    fun strip(text: String): String {
        if (text.isEmpty()) return text

        val lines = text.split('\n').map { line ->
            when {
                FENCE_LINE.containsMatchIn(line) -> null
                DIVIDER_LINE.matches(line) -> null
                else -> {
                    var out = line
                    out = HEADING_MARK.replace(out, "")
                    out = HEADING_TAIL.replace(out, "")
                    out = QUOTE_MARK.replace(out, "")
                    out = BULLET_MARK.replace(out, "")
                    out = ORDERED_MARK.replace(out, "")
                    out
                }
            }
        }
        var out = lines.filterNotNull().joinToString("\n")

        out = IMAGE.replace(out, "$1")
        out = LINK.replace(out, "$1")
        out = CODE_SPAN.replace(out, "$1")
        out = STRONG.replace(out, "$1")
        out = UNDERSCORE_STRONG.replace(out, "$1")
        out = STRIKE.replace(out, "$1")
        out = EMPHASIS.replace(out, "$1")
        out = UNDERSCORE_EMPH.replace(out, "$1")
        out = ESCAPES.replace(out, "$1")

        return out
    }
}
