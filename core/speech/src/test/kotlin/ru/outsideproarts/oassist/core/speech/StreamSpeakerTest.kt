package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSpeakerTest {

    @Test
    fun speaksCompletedIncrementally_withoutRepeats() {
        val said = mutableListOf<String>()
        val speaker = StreamSpeaker(said::add)
        speaker.onText("Привет.", false)
        speaker.onText("Привет. Как дела?", false)
        speaker.onText("Привет. Как дела? Хорошо", false)
        assertEquals(listOf("Привет.", "Как дела?"), said)
    }

    @Test
    fun finish_speaksTailWithoutDot() {
        val said = mutableListOf<String>()
        val speaker = StreamSpeaker(said::add)
        speaker.onText("Думаю", false)
        speaker.onFinish("Думаю над ответом")
        assertEquals(listOf("Думаю над ответом"), said)
    }

    @Test
    fun serviceLines_neverSpoken() {
        val said = mutableListOf<String>()
        val speaker = StreamSpeaker(said::add)
        val full = "Ищу. \n[contact_find]\n\n[contact_find: Мама]\nГотово."
        speaker.onText("Ищу. \n[contact_find]\n", false)
        speaker.onText(full, false)
        speaker.onFinish(full)
        assertEquals(listOf("Ищу.", "Готово."), said)
    }

    @Test
    fun toolComment_silenced_whenFlagOff_andNotResurrectedOnFinish() {
        val said = mutableListOf<String>()
        val speaker = StreamSpeaker(said::add, speakToolCalls = false)
        speaker.onText("Ищу контакт Мама в контактах.", true)
        speaker.onText("Ищу контакт Мама в контактах.\n[contact_find]\nВот результат.", false)
        speaker.onFinish("Ищу контакт Мама в контактах.\n[contact_find]\nВот результат.")
        assertEquals(listOf("Вот результат."), said)
    }

    @Test
    fun toolComment_spoken_whenFlagOn() {
        val said = mutableListOf<String>()
        val speaker = StreamSpeaker(said::add, speakToolCalls = true)
        speaker.onText("Ищу контакт Мама в контактах.", true)
        assertEquals(listOf("Ищу контакт Мама в контактах."), said)
    }

    @Test
    fun toolComment_capped_byBudget() {
        val said = mutableListOf<String>()
        val long = "Первое. " + "слово ".repeat(200).trim() + "."
        val speaker = StreamSpeaker(said::add, speakToolCalls = true, maxCommentChars = 50)
        speaker.onText(long, true)
        assertEquals(listOf("Первое."), said)
    }

    @Test
    fun reset_startsOver() {
        val said = mutableListOf<String>()
        val speaker = StreamSpeaker(said::add)
        speaker.onText("Раз.", false)
        speaker.reset()
        speaker.onText("Раз.", false)
        assertEquals(listOf("Раз.", "Раз."), said)
    }
}

class StripServiceLinesTest {

    @Test
    fun strips_markers_keeps_prose() {
        val text = "Ответ.\n[contact_find]\n[contact_find: Мама, +7]\n[прокси-текст] не трогаем\n[x] y"
        val stripped = SentenceSplitter.stripServiceLines(text)
        assertEquals("Ответ.\n[прокси-текст] не трогаем\n[x] y", stripped)
    }
}
