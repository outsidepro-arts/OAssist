package ru.outsideproarts.oassist.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.UiActions
import ru.outsideproarts.oassist.core.model.ImagePart

/**
 * Протокол «фолбэк vs дедлайн vs терминальный коллектор»: перестановки событий
 * assist-сессии при чёрном системном кадре. Ровно одно изображение доставляется,
 * запоздавший фолбэк игнорируется, второй колбэк снимка не продлевает дедлайн.
 */
class AssistScreenshotCoordinatorTest {

    private val system = ImagePart(imageBase64 = "system-black")
    private val fresh = ImagePart(imageBase64 = "fresh")

    private fun coordinator(): Pair<AssistScreenshotCoordinator, MutableList<Pair<ImagePart, String?>>> {
        val delivered = mutableListOf<Pair<ImagePart, String?>>()
        val coordinator = AssistScreenshotCoordinator { image, note -> delivered += image to note }
        return coordinator to delivered
    }

    @Test
    fun `фолбэк успел — доставляется свежий кадр с пометкой`() {
        val (coordinator, delivered) = coordinator()
        assertTrue(coordinator.holdForFallback(system))
        coordinator.onFallbackResult(UiActions.QuickShot(fresh, black = false, windowCapture = true))
        assertEquals(listOf(fresh to REPLACED_NOTE), delivered)
    }

    @Test
    fun `фолбэк не удался — доставляется системный кадр с честной пометкой`() {
        val (coordinator, delivered) = coordinator()
        assertTrue(coordinator.holdForFallback(system))
        coordinator.onFallbackResult(null)
        assertEquals(listOf(system to BLACK_NOTE), delivered)
    }

    @Test
    fun `дедлайн при висящем фолбэке — системный кадр с пометкой, ровно один раз`() {
        val (coordinator, delivered) = coordinator()
        assertTrue(coordinator.holdForFallback(system))
        coordinator.onDeadline()
        coordinator.onDeadline()
        assertEquals(listOf(system to BLACK_NOTE), delivered)
    }

    @Test
    fun `опоздавший фолбэк после дедлайна игнорируется`() {
        val (coordinator, delivered) = coordinator()
        assertTrue(coordinator.holdForFallback(system))
        coordinator.onDeadline()
        coordinator.onFallbackResult(UiActions.QuickShot(fresh, black = false, windowCapture = true))
        // Доставлен только системный кадр: поздний результат не подменяет закрытую сессию.
        assertEquals(listOf(system to BLACK_NOTE), delivered)
    }

    @Test
    fun `дедлайн после успешного фолбэка ничего не добавляет`() {
        val (coordinator, delivered) = coordinator()
        assertTrue(coordinator.holdForFallback(system))
        coordinator.onFallbackResult(UiActions.QuickShot(fresh, black = false, windowCapture = true))
        coordinator.onDeadline()
        assertEquals(listOf(fresh to REPLACED_NOTE), delivered)
    }

    @Test
    fun `второй колбэк снимка не стартует второй фолбэк`() {
        val (coordinator, delivered) = coordinator()
        assertTrue(coordinator.holdForFallback(system))
        assertFalse(coordinator.holdForFallback(fresh)) // дедлайн продлевается один раз
        coordinator.onFallbackResult(UiActions.QuickShot(fresh, black = false, windowCapture = true))
        assertEquals(listOf(fresh to REPLACED_NOTE), delivered)
    }

    @Test
    fun `результат фолбэка без hold игнорируется`() {
        val (coordinator, delivered) = coordinator()
        coordinator.onFallbackResult(UiActions.QuickShot(fresh, black = false, windowCapture = true))
        coordinator.onDeadline()
        assertTrue(delivered.isEmpty())
        assertFalse(coordinator.hasPendingFallback)
    }

    @Test
    fun `с коллектором AssistCapture завершение ровно одно`() {
        var completed: Pair<String?, ImagePart?>? = null
        val capture = AssistCapture<ImagePart> { context, image -> completed = context to image }
        capture.start(expectContext = false, expectScreenshot = true)
        val coordinator = AssistScreenshotCoordinator { image, _ -> capture.screenshot(image) }
        assertTrue(coordinator.holdForFallback(system))
        coordinator.onFallbackResult(UiActions.QuickShot(fresh, black = false, windowCapture = true))
        coordinator.onDeadline() // терминальный коллектор повтор отбрасывает
        capture.timeout()
        assertEquals(null to fresh, completed)
    }
}
