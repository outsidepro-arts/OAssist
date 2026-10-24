package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPlainTest {

    @Test
    fun plainText_unchanged() {
        val text = "Обычный текст с дефисами и точками."
        assertEquals(text, MarkdownPlain.strip(text))
    }

    @Test
    fun strongAndEmphasis_stripped() {
        assertEquals(
            "Это жирный и курсив текст",
            MarkdownPlain.strip("Это **жирный** и *курсив* текст"),
        )
    }

    @Test
    fun strikeAndCodeStripped() {
        assertEquals(
            "зачёркнутый код",
            MarkdownPlain.strip("~~зачёркнутый~~ `код`"),
        )
    }

    @Test
    fun link_readsLabelNotUrl() {
        assertEquals(
            "см. документацию",
            MarkdownPlain.strip("см. [документацию](https://example.com/docs)"),
        )
    }

    @Test
    fun image_readsAltNotUrl() {
        assertEquals(
            "скриншот экрана",
            MarkdownPlain.strip("![скриншот экрана](https://example.com/i.png)"),
        )
    }

    @Test
    fun headingAndListMarkers_dropped() {
        val text = "## Заголовок\n- пункт один\n1. пункт два"
        assertEquals(
            "Заголовок\nпункт один\nпункт два",
            MarkdownPlain.strip(text),
        )
    }

    @Test
    fun quoteAndDivider_dropped() {
        assertEquals(
            "цитата\nтекст",
            MarkdownPlain.strip("> цитата\n---\nтекст"),
        )
    }

    @Test
    fun fences_dropped() {
        assertEquals(
            "val x = 1",
            MarkdownPlain.strip("```kotlin\nval x = 1\n```"),
        )
    }

    @Test
    fun escapedMarkers_stayLiteral() {
        assertEquals("*не курсив*", MarkdownPlain.strip("""\*не курсив\*"""))
    }

    @Test
    fun underscoreIdentifier_untouched() {
        assertEquals(
            "переменная snake_case_name тут",
            MarkdownPlain.strip("переменная snake_case_name тут"),
        )
    }

    @Test
    fun underscoreEmphasis_stripped() {
        assertEquals("акцент слово тут", MarkdownPlain.strip("_акцент_ слово тут"))
    }
}
