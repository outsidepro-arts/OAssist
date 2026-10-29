package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedVoiceLanguageTest {

    private val voices = listOf(
        VoiceInfo("milena", "ru-RU"),
        VoiceInfo("ivan", "ru-RU"),
        VoiceInfo("en-generic", "en-US"),
    )

    @Test
    fun selectedVoice_localeWinsOverStored() {
        assertEquals(
            "ru-RU",
            selectedVoiceLanguage(voices, "milena", "en-US"),
        )
    }

    @Test
    fun noVoice_storedLocaleUsed() {
        assertEquals(
            "en-US",
            selectedVoiceLanguage(voices, null, "en-US"),
        )
        assertEquals(
            "en-US",
            selectedVoiceLanguage(voices, "", "en-US"),
        )
    }

    @Test
    fun voiceNotInList_fallsBackToStoredLocale() {
        // Голос мог не загрузиться (список пуст/неполон) — выбор не теряем.
        assertEquals(
            "ru-RU",
            selectedVoiceLanguage(emptyList(), "milena", "ru-RU"),
        )
        assertEquals(
            "ru-RU",
            selectedVoiceLanguage(listOf(voices[2]), "milena", "ru-RU"),
        )
    }

    @Test
    fun nothingSelected_isEmpty() {
        assertEquals("", selectedVoiceLanguage(voices, null, null))
        assertEquals("", selectedVoiceLanguage(emptyList(), null, null))
        assertEquals("", selectedVoiceLanguage(voices, "", ""))
    }
}
