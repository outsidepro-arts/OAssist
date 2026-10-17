package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCurtainTest {

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
    fun removeServiceComponent_dropsOnlyTarget() {
        val services = "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService:" +
            "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

        val (result, removed) = removeServiceComponent(services, TALKBACK_SERVICE_COMPONENT)

        assertTrue(removed)
        assertEquals("ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService", result)
    }

    @Test
    fun removeServiceComponent_missingTarget_reportsNothingRemoved() {
        val services = "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService"

        val (result, removed) = removeServiceComponent(services, TALKBACK_SERVICE_COMPONENT)

        assertFalse(removed)
        assertEquals(services, result)
    }

    @Test
    fun addServiceComponent_appendsWithoutDuplicates() {
        val services = "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService"

        val result = addServiceComponent(services, TALKBACK_SERVICE_COMPONENT)

        assertEquals(
            "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService:" + TALKBACK_SERVICE_COMPONENT,
            result,
        )
        assertEquals(result, addServiceComponent(result, TALKBACK_SERVICE_COMPONENT))
    }

    @Test
    fun removeServiceComponent_matchesShortNotationEntry() {
        val services = "com.google.android.marvin.talkback/.TalkBackService:" +
            "ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService"

        val (result, removed) = removeServiceComponent(services, TALKBACK_SERVICE_COMPONENT)

        assertTrue(removed)
        assertEquals("ru.outsideproarts.oassist/.a11y.OAssistAccessibilityService", result)
    }

    @Test
    fun addServiceComponent_deduplicatesAcrossNotations() {
        val services = "com.google.android.marvin.talkback/.TalkBackService"

        val result = addServiceComponent(services, TALKBACK_SERVICE_COMPONENT)

        assertEquals(services, result)
    }
}