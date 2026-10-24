package ru.outsideproarts.oassist.core.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun emptyInput_yieldsNoBlocks() {
        assertTrue(MarkdownParser.parse("").isEmpty())
    }

    @Test
    fun plainText_isSingleParagraph() {
        val blocks = MarkdownParser.parse("Просто текст без разметки.")
        assertEquals(1, blocks.size)
        val p = blocks[0] as MdBlock.Paragraph
        assertEquals(listOf<MdInline>(MdInline.Text("Просто текст без разметки.")), p.content)
    }

    @Test
    fun headings_parseLevels() {
        val blocks = MarkdownParser.parse("# Раздел\n## Подраздел\n### Третий")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
        assertEquals("Раздел", plain((blocks[0] as MdBlock.Heading).content))
    }

    @Test
    fun headingWithoutSpace_isParagraph() {
        val blocks = MarkdownParser.parse("#не-заголовок")
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun headingInterruptsParagraph_withoutBlankLine() {
        val blocks = MarkdownParser.parse("Абзац\n# Заголовок")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals(1, (blocks[1] as MdBlock.Heading).level)
    }

    @Test
    fun strongEmphasisStrikeAndCode_parse() {
        val blocks = MarkdownParser.parse("**жирный** и *курсив* и ~~зачёркнутый~~ и `код`")
        val inlines = (blocks[0] as MdBlock.Paragraph).content
        assertTrue(inlines[0] is MdInline.Strong)
        assertTrue(inlines.any { it is MdInline.Emphasis })
        assertTrue(inlines.any { it is MdInline.Strikethrough })
        assertTrue(inlines.any { it is MdInline.CodeSpan })
        assertEquals("жирный", plain((inlines[0] as MdInline.Strong).content))
    }

    @Test
    fun nestedStrongEmphasis() {
        val inlines = MarkdownParser.parseInlines("**жирный _и курсив_**")
        assertEquals(1, inlines.size)
        val strong = inlines[0] as MdInline.Strong
        assertTrue(strong.content[0] is MdInline.Text)
        assertTrue(strong.content.any { it is MdInline.Emphasis })
    }

    @Test
    fun underscoreInIdentifier_staysLiteral() {
        val inlines = MarkdownParser.parseInlines("переменная snake_case_name тут")
        assertEquals(1, inlines.size)
        assertEquals("переменная snake_case_name тут", (inlines[0] as MdInline.Text).text)
    }

    @Test
    fun link_andImage() {
        val inlines = MarkdownParser.parseInlines("см. [документацию](https://example.com/docs) и ![скрин](https://example.com/i.png)")
        val link = inlines[1] as MdInline.Link
        assertEquals("документацию", plain(link.label))
        assertEquals("https://example.com/docs", link.url)
        val image = inlines[3] as MdInline.Image
        assertEquals("скрин", image.alt)
        assertEquals("https://example.com/i.png", image.url)
    }

    @Test
    fun unbalancedMarkers_stayLiteral() {
        val inlines = MarkdownParser.parseInlines("это **незакрытый текст")
        assertEquals(1, inlines.size)
        assertEquals("это **незакрытый текст", (inlines[0] as MdInline.Text).text)
    }

    @Test
    fun escape_removesMarker() {
        val inlines = MarkdownParser.parseInlines("\\*не курсив\\*")
        assertEquals(1, inlines.size)
        assertEquals("*не курсив*", (inlines[0] as MdInline.Text).text)
    }

    @Test
    fun bulletList_groupsConsecutiveItems() {
        val blocks = MarkdownParser.parse("- первый\n- второй\n\nпосле")
        assertEquals(2, blocks.size)
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(2, list.items.size)
        assertEquals("первый", plain(list.items[0]))
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun orderedList_keepsNumbers() {
        val blocks = MarkdownParser.parse("1. один\n2. два")
        val list = blocks[0] as MdBlock.OrderedList
        assertEquals(1, list.items[0].number)
        assertEquals(2, list.items[1].number)
        assertEquals("два", plain(list.items[1].content))
    }

    @Test
    fun blockquote_quotesConsecutiveLines() {
        val blocks = MarkdownParser.parse("> первая строка\n> вторая строка\n\nтекст")
        assertEquals(2, blocks.size)
        val quote = blocks[0] as MdBlock.Quote
        assertEquals("первая строка\nвторая строка", plain(quote.content))
    }

    @Test
    fun fencedCode_keepsContentAndLanguage() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\nprintln(\"**не разметка**\")\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("**не разметка**"))
        assertTrue(code.code.contains("val x = 1"))
    }

    @Test
    fun unterminatedFence_keepsRestAsCode() {
        val blocks = MarkdownParser.parse("```\nобрывок кода")
        val code = blocks[0] as MdBlock.Code
        assertEquals("обрывок кода", code.code)
    }

    @Test
    fun divider_parses() {
        val blocks = MarkdownParser.parse("до\n---\nпосле")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Divider)
    }

    @Test
    fun crlf_isHandled() {
        val blocks = MarkdownParser.parse("первый\r\n\r\nвторой")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    private fun plain(inlines: List<MdInline>): String = inlines.joinToString("") {
        when (it) {
            is MdInline.Text -> it.text
            is MdInline.CodeSpan -> it.text
            is MdInline.Strong -> plain(it.content)
            is MdInline.Emphasis -> plain(it.content)
            is MdInline.Strikethrough -> plain(it.content)
            is MdInline.Link -> plain(it.label)
            is MdInline.Image -> it.alt
        }
    }
}
