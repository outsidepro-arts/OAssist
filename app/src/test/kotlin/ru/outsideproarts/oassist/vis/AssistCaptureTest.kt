package ru.outsideproarts.oassist.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.ImagePart

class AssistCaptureTest {
    @Before fun prepare() = AssistSessionStore.clear()
    @After fun cleanUp() = AssistSessionStore.clear()

    @Test
    fun bothCallbackOrdersDeliverMatchingPayloadToTheSessionExactlyOnce() {
        for (screenshotFirst in listOf(false, true)) {
            AssistSessionStore.clear()
            val owner = AssistSessionStore.beginScreenCapture()
            val tokens = mutableListOf<String>()
            val capture = AssistCapture<ImagePart> { context, image ->
                tokens += requireNotNull(AssistSessionStore.publishScreenCapture(owner, context, image))
            }
            val image = ImagePart("image")
            capture.start(expectContext = true, expectScreenshot = true)
            if (screenshotFirst) capture.screenshot(image) else capture.context("screen")
            assertEquals(0, tokens.size)
            if (screenshotFirst) capture.context("screen") else capture.screenshot(image)
            capture.context("late context")
            capture.screenshot(ImagePart("late image"))
            assertEquals(1, tokens.size)
            val session = requireNotNull(AssistSessionStore.consumeScreenCapture(tokens.single(), true))
            assertEquals("screen", session.screenContext)
            assertEquals(image, session.screenImage)
        }
    }

    @Test
    fun nullScreenshotCompletesItsChannel() {
        val results = mutableListOf<Pair<String?, String?>>()
        val capture = AssistCapture<String> { context, screenshot -> results += context to screenshot }
        capture.start(expectContext = true, expectScreenshot = true)
        capture.screenshot(null)
        assertEquals(emptyList<Pair<String?, String?>>(), results)
        capture.context("screen")
        assertEquals(listOf("screen" to null), results)
        capture.screenshot("late")
        assertEquals(1, results.size)
    }

    @Test
    fun disabledScreenshotDoesNotWaitOrAcceptIt() {
        val results = mutableListOf<Pair<String?, String?>>()
        val capture = AssistCapture<String> { context, screenshot -> results += context to screenshot }
        capture.start(expectContext = true, expectScreenshot = false)
        capture.screenshot("ignored")
        capture.context("screen")
        assertEquals(listOf("screen" to null), results)
    }

    @Test
    fun timeoutDeliversOnlyReceivedDataAndIgnoresLateCallbacks() {
        val results = mutableListOf<Pair<String?, String?>>()
        val capture = AssistCapture<String> { context, screenshot -> results += context to screenshot }
        capture.start(expectContext = true, expectScreenshot = true)
        capture.context("screen")
        capture.timeout()
        capture.screenshot("late")
        capture.timeout()
        assertEquals(listOf("screen" to null), results)
    }

    @Test
    fun overlappingShowCancelsCaptureAndCannotBeRestarted() {
        val results = mutableListOf<Pair<String?, String?>>()
        val capture = AssistCapture<String> { context, screenshot -> results += context to screenshot }
        capture.start(expectContext = true, expectScreenshot = true)
        capture.context("old")
        assertFalse(capture.start(expectContext = true, expectScreenshot = true))
        capture.screenshot("late")
        capture.timeout()
        assertEquals(emptyList<Pair<String?, String?>>(), results)
    }

    @Test
    fun cancellationDiscardsPendingData() {
        val results = mutableListOf<Pair<String?, String?>>()
        val capture = AssistCapture<String> { context, screenshot -> results += context to screenshot }
        capture.start(expectContext = true, expectScreenshot = true)
        capture.screenshot("image")
        capture.cancel()
        capture.context("late")
        capture.timeout()
        assertEquals(emptyList<Pair<String?, String?>>(), results)
    }

    @Test
    fun settingsFailureDisablesScreenshotInsteadOfAttachingItOrWaiting() {
        val results = mutableListOf<Pair<String?, String?>>()
        val capture = AssistCapture<String> { context, image -> results += context to image }
        capture.start(true, true, screenshotEnabled = { error("settings unavailable") })
        capture.screenshot("must not attach")
        capture.context("screen")
        assertEquals(listOf("screen" to null), results)
    }

    @Test
    fun absentFlagsAndMissingCallbacksStillCompleteOnce() {
        val results = mutableListOf<Pair<String?, String?>>()
        val noFlags = AssistCapture<String> { context, image -> results += context to image }
        noFlags.start(false, false)
        noFlags.context("unsolicited")
        noFlags.screenshot("unsolicited")
        noFlags.timeout()
        assertEquals(listOf(null to null), results)
        results.clear()
        val missing = AssistCapture<String> { context, image -> results += context to image }
        missing.start(true, true)
        missing.timeout()
        missing.context("late")
        missing.screenshot("late")
        assertEquals(listOf(null to null), results)
    }

    @Test
    fun lateCallbacksFromCancelledInstanceCannotModifyFreshInstanceHandoff() {
        val oldOwner = AssistSessionStore.beginScreenCapture()
        val old = AssistCapture<ImagePart> { context, image ->
            error("Cancelled instance delivered $context / $image")
        }
        old.start(true, true)
        old.context("old context")
        old.cancel()
        AssistSessionStore.cancelScreenCapture(oldOwner)

        val newOwner = AssistSessionStore.beginScreenCapture()
        var token: String? = null
        val fresh = AssistCapture<ImagePart> { context, image ->
            token = AssistSessionStore.publishScreenCapture(newOwner, context, image)
        }
        fresh.start(true, true)
        old.screenshot(ImagePart("old screenshot"))
        old.context("old context")
        old.timeout()
        fresh.context("new context")
        assertNull(token)
        fresh.screenshot(null)
        val session = requireNotNull(AssistSessionStore.consumeScreenCapture(requireNotNull(token), true))
        assertEquals("new context", session.screenContext)
        assertNull(session.screenImage)
    }
}
