package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReaderSpeakerTest {

    @Test
    fun announcesInOrder_blankIgnored() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("Первое.")
        speaker.announce("   ")
        speaker.announce("Второе.")
        runCurrent()
        assertEquals(listOf("Первое.", "Второе."), said)
        assertFalse(speaker.speaking.value)
    }

    @Test
    fun identicalSentences_notSwallowed() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("Готово.")
        speaker.announce("Готово.")
        runCurrent()
        assertEquals(listOf("Готово.", "Готово."), said)
    }

    @Test
    fun stop_dropsPending_keepsSpoken() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var parked = false
        val speaker = ScreenReaderSpeaker(
            scope = this,
            output = said::add,
            delayer = {
                if (!parked) {
                    parked = true
                    gate.await()
                }
            },
        )
        speaker.announce("Первое.")
        speaker.announce("Второе.")
        runCurrent()
        assertEquals(listOf("Первое."), said)
        assertTrue(speaker.speaking.value)
        speaker.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Первое."), said)
        assertFalse(speaker.speaking.value)
    }

    @Test
    fun assertive_clearsQueue_speaksImmediately() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var parked = false
        val speaker = ScreenReaderSpeaker(
            scope = this,
            output = said::add,
            delayer = {
                if (!parked) {
                    parked = true
                    gate.await()
                }
            },
        )
        speaker.announce("Первое.")
        speaker.announce("Второе.")
        runCurrent()
        speaker.announceAssertive("Ошибка!")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Первое.", "Ошибка!"), said)
        assertFalse(speaker.speaking.value)
    }
}
