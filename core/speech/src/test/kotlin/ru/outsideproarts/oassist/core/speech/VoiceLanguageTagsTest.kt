package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLanguageTagsTest {

    @Test
    fun distinct_sorted() {
        val voices = listOf(
            VoiceInfo("v3", "en-US"),
            VoiceInfo("v1", "ru-RU"),
            VoiceInfo("v2", "en-US"),
            VoiceInfo("v4", "de-DE"),
        )

        assertEquals(listOf("de-DE", "en-US", "ru-RU"), voiceLanguageTags(voices))
    }

    @Test
    fun empty_isEmpty() {
        assertEquals(emptyList<String>(), voiceLanguageTags(emptyList()))
    }
}
