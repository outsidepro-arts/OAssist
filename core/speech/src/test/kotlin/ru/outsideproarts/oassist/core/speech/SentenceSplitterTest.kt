package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun splitSentences_simple() {
        assertEquals(
            listOf("Привет.", "Как дела?"),
            SentenceSplitter.splitSentences("Привет. Как дела?"),
        )
    }

    @Test
    fun splitSentences_trimsAndDropsEmpty() {
        assertEquals(
            listOf("Раз.", "Два!"),
            SentenceSplitter.splitSentences("  Раз.   Два!  "),
        )
    }

    @Test
    fun splitSentences_ellipsisBoundary() {
        assertEquals(
            listOf("Думаю…", "Готово."),
            SentenceSplitter.splitSentences("Думаю… Готово."),
        )
    }

    @Test
    fun splitSentences_blank_isEmpty() {
        assertTrue(SentenceSplitter.splitSentences("   ").isEmpty())
    }

    @Test
    fun completePrefix_returnsOnlyFinished() {
        assertEquals(
            "Привет.",
            SentenceSplitter.completePrefix("Привет. Как де"),
        )
    }

    @Test
    fun completePrefix_trailingSpace_countsAsFinished() {
        assertEquals(
            "Привет.",
            SentenceSplitter.completePrefix("Привет. "),
        )
    }

    @Test
    fun completePrefix_terminalPunctuation_countsAsFinished() {
        assertEquals(
            "Ищу контакт Мама в контактах.",
            SentenceSplitter.completePrefix("Ищу контакт Мама в контактах."),
        )
    }

    @Test
    fun completePrefix_noBoundary_isEmpty() {
        assertEquals("", SentenceSplitter.completePrefix("Привет"))
    }

    @Test
    fun completePrefix_blank_isEmpty() {
        assertEquals("", SentenceSplitter.completePrefix("   "))
    }

    @Test
    fun completePrefix_multipleSentences() {
        assertEquals(
            "Раз. Два!",
            SentenceSplitter.completePrefix("Раз. Два! Три"),
        )
    }
}
