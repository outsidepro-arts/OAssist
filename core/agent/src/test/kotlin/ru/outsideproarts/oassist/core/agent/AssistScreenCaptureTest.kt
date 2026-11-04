package ru.outsideproarts.oassist.core.agent

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart

class AssistScreenCaptureTest {
    @Before fun prepare() = AssistSessionStore.clear()
    @After fun cleanUp() = AssistSessionStore.clear()

    private fun publish(context: String? = "screen", image: ImagePart? = ImagePart("image")): String =
        requireNotNull(AssistSessionStore.publishScreenCapture(AssistSessionStore.beginScreenCapture(), context, image))

    @Test fun deliveryIsOneShotAndNullSnapshotClearsPreviousScreenWithoutLosingHistory() {
        val target = AssistSessionStore.getOrCreate()
        target.screenContext = "old"
        target.screenImage = ImagePart("old image")
        target.appendTurn(ChatMessage(ChatRole.USER, "question"), emptyList())
        val token = publish(null, null)
        assertSame(target, AssistSessionStore.consumeScreenCapture(token, true))
        assertNull(target.screenContext)
        assertNull(target.screenImage)
        assertEquals("question", target.history.single().content)
        assertNull(AssistSessionStore.consumeScreenCapture(token, true))
    }

    @Test fun activeUiModeSurvivesCaptureHandoffAndRejectsStaleOwner() {
        val first = AssistSessionStore.getOrCreate()
        assertNull(AssistSessionStore.activeShowOverlay())
        AssistSessionStore.activateUi(first, false)
        val token = publish()
        assertSame(first, AssistSessionStore.current())
        assertEquals(false, AssistSessionStore.activeShowOverlay())
        assertSame(first, AssistSessionStore.consumeScreenCapture(token, true))
        assertEquals(false, AssistSessionStore.activeShowOverlay())

        val staleToken = publish()
        AssistSessionStore.clear(first)
        assertNull(AssistSessionStore.activeShowOverlay())
        assertNull(AssistSessionStore.consumeScreenCapture(staleToken, true))
        val second = AssistSessionStore.getOrCreate()
        AssistSessionStore.activateUi(second, true)
        AssistSessionStore.activateUi(first, false)
        AssistSessionStore.clear(first)
        assertSame(second, AssistSessionStore.current())
        assertEquals(true, AssistSessionStore.activeShowOverlay())
    }

    @Test fun revokedScreenshotSettingRemovesPreviouslyCollectedImage() {
        val token = publish()
        val target = requireNotNull(AssistSessionStore.consumeScreenCapture(token, false))
        assertEquals("screen", target.screenContext)
        assertNull(target.screenImage)
    }

    @Test fun largePayloadStaysAvailableUntilMatchingTokenConsumesIt() {
        val image = ImagePart("sensitive".repeat(200_000))
        val token = publish(image = image)
        assertTrue(token.length < 100)
        assertTrue(AssistSessionStore.isScreenCapturePending(token))
        assertNull(AssistSessionStore.consumeScreenCapture("unknown token", true))
        assertNull(AssistSessionStore.current())
        val target = requireNotNull(AssistSessionStore.consumeScreenCapture(token, true))
        assertSame(image, target.screenImage)
    }

    @Test fun latePermissionAfterOwnerReplacementCannotModifyNewOwner() {
        val first = AssistSessionStore.getOrCreate()
        val token = publish()
        AssistSessionStore.clear()
        val second = AssistSessionStore.getOrCreate()
        second.screenContext = "new owner"
        assertFalse(AssistSessionStore.isScreenCapturePending(token))
        assertNull(AssistSessionStore.consumeScreenCapture(token, true))
        AssistSessionStore.clear(first)
        assertSame(second, AssistSessionStore.current())
        assertEquals("new owner", second.screenContext)
    }

    @Test fun nullOwnerAbaDoesNotRestorePermissionToDeliver() {
        val token = publish()
        AssistSessionStore.getOrCreate()
        AssistSessionStore.clear()
        assertNull(AssistSessionStore.consumeScreenCapture(token, true))
        assertNull(AssistSessionStore.current())
    }

    @Test fun ownerChangeWhileCaptureIsCollectingRejectsPublication() {
        val owner = AssistSessionStore.beginScreenCapture()
        AssistSessionStore.getOrCreate()
        AssistSessionStore.clear()
        assertNull(AssistSessionStore.publishScreenCapture(owner, "late", ImagePart("late")))
    }

    @Test fun newGestureInvalidatesOlderPermissionAndCaptureWithoutLettingOldCleanupCancelIt() {
        val oldToken = publish()
        val oldOwner = AssistSessionStore.beginScreenCapture()
        val newOwner = AssistSessionStore.beginScreenCapture()
        AssistSessionStore.cancelScreenCapture(oldOwner)
        assertNull(AssistSessionStore.publishScreenCapture(oldOwner, "old", null))
        val newToken = requireNotNull(AssistSessionStore.publishScreenCapture(newOwner, "new", null))
        AssistSessionStore.discardScreenCapture(oldToken)
        assertNull(AssistSessionStore.consumeScreenCapture(oldToken, true))
        assertEquals("new", AssistSessionStore.consumeScreenCapture(newToken, true)?.screenContext)
    }

    @Test fun expiredOrLostPayloadCannotCreateSession() {
        val token = publish()
        AssistSessionStore.discardScreenCapture(token)
        assertNull(AssistSessionStore.consumeScreenCapture(token, true))
        assertNull(AssistSessionStore.current())
    }
}
