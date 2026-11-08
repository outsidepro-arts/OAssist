package ru.outsideproarts.oassist.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.UiActions
import ru.outsideproarts.oassist.core.model.ImagePart

/**
 * Выбор скриншота assist-сессии при чёрном системном кадре: фолбэк через наш сервис
 * против честной пометки. Чёрный кадр не выбрасывается никогда — тёмное содержимое
 * может быть легитимным. Пометка не должна противоречить приложенному кадру.
 */
class AssistScreenshotPolicyTest {

    private val system = ImagePart(imageBase64 = "system")
    private val fresh = ImagePart(imageBase64 = "fresh")

    @Test
    fun `снимок окна заменяет чёрный системный кадр и помечает шторку`() {
        val choice = chooseAssistScreenshot(
            system,
            UiActions.QuickShot(fresh, black = false, windowCapture = true),
        )
        assertEquals(fresh, choice.image)
        assertEquals(REPLACED_NOTE, choice.note)
    }

    @Test
    fun `неудавшийся фолбэк оставляет системный кадр с честной пометкой`() {
        val choice = chooseAssistScreenshot(system, null)
        assertEquals(system, choice.image)
        assertEquals(BLACK_NOTE, choice.note)
    }

    @Test
    fun `чёрный фолбэк-кадр не выбрасывается и не утверждает шторку`() {
        val choice = chooseAssistScreenshot(
            system,
            UiActions.QuickShot(fresh, black = true, windowCapture = true),
        )
        assertEquals(fresh, choice.image)
        assertEquals(BLACK_NOTE, choice.note)
    }

    @Test
    fun `display-фолбэк переснимает без категоричных утверждений`() {
        // Контраста «экран чёрный / окно не чёрное» нет (API ≤ 33 или окно не найдено):
        // кадр свежий — пометка не должна называть его чёрным и отправлять к пользователю.
        val choice = chooseAssistScreenshot(
            system,
            UiActions.QuickShot(fresh, black = false, windowCapture = false),
        )
        assertEquals(fresh, choice.image)
        assertEquals(RESHOT_NOTE, choice.note)
        assertFalse(choice.note.contains("без обработки"))
    }

    @Test
    fun `пометки честны про причину и ограничения`() {
        assertTrue(REPLACED_NOTE.contains("шторк"))
        assertTrue(BLACK_NOTE.contains("шторк"))
        assertTrue(BLACK_NOTE.contains("возможно")) // не выдаёт предположение за факт
        // «без обработки» — истина в обоих ветках BLACK_NOTE (системный кадр или переснятый чёрный).
        assertTrue(BLACK_NOTE.contains("без обработки"))
        // Замена подтверждена контрастом «экран чёрный, окно не чёрное» — без утверждений о чёрном кадре.
        assertFalse(REPLACED_NOTE.contains("без обработки"))
        assertFalse(RESHOT_NOTE.contains("без обработки"))
    }

    @Test
    fun `подтверждённый attach прикрепляет снимок и пометку о пересъёме`() {
        val payload = assistHandoffPayload("screen", fresh, REPLACED_NOTE, attachScreenshot = true)
        assertEquals(fresh, payload.image)
        assertEquals("screen\n$REPLACED_NOTE", payload.context)
    }

    @Test
    fun `отключённый attachScreenshot не прикрепляет снимок и не публикует пометку`() {
        val payload = assistHandoffPayload("screen", fresh, REPLACED_NOTE, attachScreenshot = false)
        assertNull(payload.image)
        // Пометка описывает прикреплённый кадр — без кадра она ложь.
        assertEquals("screen", payload.context)
    }

    @Test
    fun `непрочитанное решение не прикрепляет снимок`() {
        val payload = assistHandoffPayload("screen", fresh, REPLACED_NOTE, attachScreenshot = null)
        assertNull(payload.image)
        assertEquals("screen", payload.context)
    }

    @Test
    fun `без пометки контекст публикуется как есть`() {
        val payload = assistHandoffPayload("screen", fresh, null, attachScreenshot = true)
        assertEquals(fresh, payload.image)
        assertEquals("screen", payload.context)
    }

    @Test
    fun `быстрый колбэк снимка ждёт асинхронное решение и отдаёт кадр при attach`() {
        // Гонка, ловившаяся на устройстве: снимок приходил раньше чтения конфига и молча терялся.
        val submitted = mutableListOf<ImagePart?>()
        val submission = AssistScreenshotSubmission { submitted += it }
        submission.onScreenshot(fresh)
        assertEquals(emptyList<ImagePart?>(), submitted)
        submission.onDecision(attach = true)
        assertEquals(listOf<ImagePart?>(fresh), submitted)
    }

    @Test
    fun `решение false после снимка не прикрепляет кадр и не повторяется`() {
        val submitted = mutableListOf<ImagePart?>()
        val submission = AssistScreenshotSubmission { submitted += it }
        submission.onScreenshot(fresh)
        submission.onDecision(attach = false)
        submission.onScreenshot(ImagePart(imageBase64 = "late"))
        submission.onDecision(attach = true)
        assertEquals(listOf<ImagePart?>(null), submitted)
    }

    @Test
    fun `решение false без снимка закрывает канал сразу`() {
        // Скриншоты выключены — колбэк снимка не ждём (прежняя семантика screenshotEnabled).
        val submitted = mutableListOf<ImagePart?>()
        val submission = AssistScreenshotSubmission { submitted += it }
        submission.onDecision(attach = false)
        assertEquals(listOf<ImagePart?>(null), submitted)
    }

    @Test
    fun `решение true без снимка продолжает ждать колбэк`() {
        val submitted = mutableListOf<ImagePart?>()
        val submission = AssistScreenshotSubmission { submitted += it }
        submission.onDecision(attach = true)
        assertEquals(emptyList<ImagePart?>(), submitted)
        submission.onScreenshot(null)
        assertEquals(listOf<ImagePart?>(null), submitted)
    }

    @Test
    fun `решение true до снимка отдаёт первый же кадр`() {
        val submitted = mutableListOf<ImagePart?>()
        val submission = AssistScreenshotSubmission { submitted += it }
        submission.onDecision(attach = true)
        submission.onScreenshot(fresh)
        submission.onScreenshot(ImagePart(imageBase64 = "second"))
        assertEquals(listOf<ImagePart?>(fresh), submitted)
    }
}
