package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {

    @Test
    fun emptyQuery_matchesEverything() {
        assertEquals(0, FuzzyMatcher.score("", "Рецепты"))
        assertEquals(0, FuzzyMatcher.score("   ", "Рецепты"))
    }

    @Test
    fun subsequence_matchesAbbreviation() {
        assertNotNull(FuzzyMatcher.score("рцп", "Рецепты"))
        assertNotNull(FuzzyMatcher.score("рп", "Рецепты"))
    }

    @Test
    fun missingLetter_doesNotMatch() {
        assertNull(FuzzyMatcher.score("рцпя", "Рецепты"))
        assertNull(FuzzyMatcher.score("xyz", "Рецепты"))
    }

    @Test
    fun substring_beatsScatteredSubsequence() {
        val full = FuzzyMatcher.score("рецепт", "Рецепты на зиму")!!
        val scattered = FuzzyMatcher.score("рзм", "Рецепты на зиму")!!
        assertTrue("full=$full scattered=$scattered", full > scattered)
    }

    @Test
    fun wordStart_beatsMidWord() {
        val wordStart = FuzzyMatcher.score("зим", "Рецепты на зиму")!!
        val midWord = FuzzyMatcher.score("иму", "Рецепты на зиму")!!
        assertTrue(wordStart > midWord)
    }

    @Test
    fun earlierMatch_ranksHigher() {
        val early = FuzzyMatcher.score("чат", "Чат с агентом")!!
        val late = FuzzyMatcher.score("чат", "Длинное название с чатом")!!
        assertTrue(early > late)
    }

    @Test
    fun allTokens_mustMatch() {
        assertNull(FuzzyMatcher.score("рецепты космос", "Рецепты на зиму"))
        assertNotNull(FuzzyMatcher.score("рецепты зиму", "Рецепты на зиму"))
    }

    @Test
    fun rank_ordersByScoreAndFiltersMisses() {
        val items = listOf("Рецепты на зиму", "Работа", "Планы на лето")
        val ranked = FuzzyMatcher.rank("рцп", items) { it }
        assertEquals(listOf("Рецепты на зиму"), ranked)
    }

    @Test
    fun caseInsensitive() {
        assertNotNull(FuzzyMatcher.score("РЕЦЕПТЫ", "рецепты"))
    }
}
