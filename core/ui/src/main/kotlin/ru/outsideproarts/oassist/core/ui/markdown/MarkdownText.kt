package ru.outsideproarts.oassist.core.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Ответ модели с Markdown-разметкой: одна текстовая нода (одно смахивание
 * в TalkBack — весь текст читается одним объектом), ссылки — `LinkAnnotation`
 * (озвучиваются как ссылки, активируются, подчёркнуты). Заголовки/жирный/код —
 * визуальные стили внутри одной ноды.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outline
    val annotated = remember(text, style, linkColor, codeBackground, quoteColor, dividerColor) {
        renderMarkdown(
            text = text,
            style = style,
            linkColor = linkColor,
            codeBackground = codeBackground,
            quoteColor = quoteColor,
            dividerColor = dividerColor,
        )
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

/**
 * Сборка AnnotatedString из AST. Разбивает на блоки: абзацы идут подряд с
 * переводом строки, после заголовков/кода/разделителей — пустая строка.
 * Парсинг чистый (без Compose) — тестируется в `MarkdownParserTest`.
 */
internal fun renderMarkdown(
    text: String,
    style: TextStyle,
    linkColor: Color,
    codeBackground: Color,
    quoteColor: Color,
    dividerColor: Color,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    // Экстремально длинный текст не парсим (защита от лагов рекомпозиции).
    if (text.length > MAX_RENDER_CHARS) return AnnotatedString(text)
    val blocks = MarkdownParser.parse(text)
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        blocks.forEachIndexed { index, block ->
            appendBlock(
                block = block,
                baseStyle = style,
                linkStyles = linkStyles,
                codeBackground = codeBackground,
                quoteColor = quoteColor,
                dividerColor = dividerColor,
                depth = 0,
            )
            if (index != blocks.lastIndex) {
                // Список каждый элемент заканчивает '\n' — достаточно одного перевода.
                append(if (block is MdBlock.BulletList || block is MdBlock.OrderedList) "\n" else "\n\n")
            }
        }
    }
}

private const val MAX_RENDER_CHARS = 100_000
private const val MAX_INLINE_DEPTH = 12

private fun AnnotatedString.Builder.appendBlock(
    block: MdBlock,
    baseStyle: TextStyle,
    linkStyles: TextLinkStyles,
    codeBackground: Color,
    quoteColor: Color,
    dividerColor: Color,
    depth: Int,
) {
    when (block) {
        is MdBlock.Heading -> {
            val size = headingSize(baseStyle, block.level)
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = size,
                ),
            ) { appendInlines(block.content, linkStyles, codeBackground, depth) }
        }
        is MdBlock.Paragraph ->
            appendInlines(block.content, linkStyles, codeBackground, depth)
        is MdBlock.Code ->
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(block.code)
            }
        is MdBlock.Quote ->
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = quoteColor)) {
                appendInlines(block.content, linkStyles, codeBackground, depth)
            }
        is MdBlock.BulletList ->
            block.items.forEach { item ->
                append("•  ")
                appendInlines(item, linkStyles, codeBackground, depth)
                append('\n')
            }
        is MdBlock.OrderedList ->
            block.items.forEach { item ->
                append("${item.number}.  ")
                appendInlines(item.content, linkStyles, codeBackground, depth)
                append('\n')
            }
        is MdBlock.Divider ->
            withStyle(SpanStyle(color = dividerColor)) {
                append("─".repeat(24))
            }
    }
}

private fun headingSize(base: TextStyle, level: Int): TextUnit {
    if (!base.fontSize.isSpecified) return TextUnit.Unspecified
    val factor = when (level) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        4 -> 1.05f
        5 -> 1.0f
        else -> 1.0f
    }
    return base.fontSize * factor
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MdInline>,
    linkStyles: TextLinkStyles,
    codeBackground: Color,
    depth: Int,
) {
    if (depth > MAX_INLINE_DEPTH) {
        inlines.forEach { append(plainText(it)) }
        return
    }
    inlines.forEach { inline ->
        when (inline) {
            is MdInline.Text -> append(inline.text)
            is MdInline.Strong ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlines(inline.content, linkStyles, codeBackground, depth + 1)
                }
            is MdInline.Emphasis ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlines(inline.content, linkStyles, codeBackground, depth + 1)
                }
            is MdInline.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlines(inline.content, linkStyles, codeBackground, depth + 1)
                }
            is MdInline.CodeSpan ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                    append(inline.text)
                }
            is MdInline.Link -> {
                val start = length
                appendInlines(inline.label, linkStyles, codeBackground, depth + 1)
                if (length > start) {
                    addLink(LinkAnnotation.Url(inline.url, linkStyles), start, length)
                }
            }
            is MdInline.Image -> {
                val label = inline.alt.ifBlank { inline.url }
                val start = length
                append(label)
                addLink(LinkAnnotation.Url(inline.url, linkStyles), start, length)
            }
        }
    }
}

/** Плоский текст узла (для переполненной глубины/фолбэка). */
private fun plainText(inline: MdInline): String = when (inline) {
    is MdInline.Text -> inline.text
    is MdInline.Strong -> inline.content.joinToString("") { plainText(it) }
    is MdInline.Emphasis -> inline.content.joinToString("") { plainText(it) }
    is MdInline.Strikethrough -> inline.content.joinToString("") { plainText(it) }
    is MdInline.CodeSpan -> inline.text
    is MdInline.Link -> inline.label.joinToString("") { plainText(it) }
    is MdInline.Image -> inline.alt.ifBlank { inline.url }
}
