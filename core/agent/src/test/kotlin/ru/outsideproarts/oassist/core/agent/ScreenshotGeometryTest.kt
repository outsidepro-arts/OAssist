package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotGeometryTest {
    // Every pixel encodes its screen position, so a shifted crop changes its contents.
    private val display = List(8) { y -> List(10) { x -> "$x,$y" } }
    private val window = UiRect(3, 2, 8, 6)
    private val windowPixels = display.slice(2 until 6).map { it.slice(3 until 8) }

    private fun crop(pixels: List<List<String>>, bounds: UiRect?): List<List<String>> =
        if (bounds == null) pixels else pixels.slice(bounds.top until bounds.bottom)
            .map { it.slice(bounds.left until bounds.right) }

    @Test
    fun displayAndWindowSourcesProduceSameFrameAndRegionAtNonzeroOrigin() {
        for (api in listOf(33, 34, 35)) {
            val source = screenshotSource(api, hasWindow = true)
            val captured = if (source == ScreenshotSource.WINDOW) windowPixels else display
            val frame = crop(captured, screenshotFrameCrop(source, window))
            assertEquals(windowPixels, frame)
            val region = UiRect(4, 3, 7, 5)
            assertEquals(
                listOf(listOf("4,3", "5,3", "6,3"), listOf("4,4", "5,4", "6,4")),
                crop(frame, screenshotRegionCrop(region, window)),
            )
        }
    }

    @Test
    fun regionAtWindowEdgesAndSinglePixelKeepExclusiveRightAndBottom() {
        assertEquals(UiRect(0, 0, 5, 4), screenshotRegionCrop(window, window))
        val lastPixel = screenshotRegionCrop(UiRect(7, 5, 8, 6), window)
        assertEquals(listOf(listOf("7,5")), crop(windowPixels, lastPixel))
    }

    @Test
    fun absentWindowUsesUncroppedDisplayOnBothApiPaths() {
        for (api in listOf(33, 34)) {
            val source = screenshotSource(api, hasWindow = false)
            assertEquals(ScreenshotSource.DISPLAY, source)
            assertNull(screenshotFrameCrop(source, null))
            assertNull(screenshotRegionCrop(UiRect(1, 1, 2, 2), null))
        }
    }
}
