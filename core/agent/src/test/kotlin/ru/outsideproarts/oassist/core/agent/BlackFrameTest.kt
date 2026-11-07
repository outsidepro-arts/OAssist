package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Детекция чёрного кадра и пометки к нему. Прежние тесты `removeServiceComponent`/
 * `addServiceComponent` сняты вместе с самими функциями: механизм временного выключения
 * скринридера ради снимка отменён требованием «скринридеры не трогаем», функции
 * использовались только им (нотация компонент покрыта `EnabledServicesTest`).
 */
class BlackFrameTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun allBlack_isMostlyBlack() {
        assertTrue(isMostlyBlack(IntArray(100) { black }))
    }

    @Test
    fun brightContent_notBlack() {
        assertFalse(isMostlyBlack(IntArray(100) { white }))
    }

    @Test
    fun mixedBelowThreshold_notBlack() {
        val pixels = IntArray(100) { i -> if (i < 90) black else white }
        assertFalse(isMostlyBlack(pixels))
    }

    @Test
    fun mixedAboveThreshold_isBlack() {
        val pixels = IntArray(100) { i -> if (i < 99) black else white }
        assertTrue(isMostlyBlack(pixels))
    }

    @Test
    fun empty_isBlack() {
        assertTrue(isMostlyBlack(IntArray(0)))
    }

    @Test
    fun displayCaptureBlackFrame_mentionsCurtain() {
        val note = blackFrameNote(windowCapture = false)
        assertTrue(note.contains("шторк"))
        assertTrue(note.contains("сло")) // кадр собран из всех слоёв экрана — шторка входит в него
    }

    @Test
    fun windowCaptureBlackFrame_doesNotClaimCurtain() {
        // Оконный захват не содержит чужие оверлеи — шторка им исключена, утверждать о ней нельзя.
        val note = blackFrameNote(windowCapture = true)
        assertFalse(note.contains("шторк"))
        assertTrue(note.contains("тёмное содержимое"))
    }

    @Test
    fun notesDifferByCaptureKind() {
        assertTrue(blackFrameNote(false) != blackFrameNote(true))
    }
}
