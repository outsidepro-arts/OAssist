package ru.outsideproarts.oassist.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
