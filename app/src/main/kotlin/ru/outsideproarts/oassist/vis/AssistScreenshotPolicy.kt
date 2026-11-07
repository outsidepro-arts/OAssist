package ru.outsideproarts.oassist.vis

import ru.outsideproarts.oassist.core.agent.UiActions
import ru.outsideproarts.oassist.core.model.ImagePart

/** Выбранный для assist-сессии скриншот и честная пометка к нему. */
internal data class AssistScreenshotChoice(val image: ImagePart, val note: String)

/**
 * Выбор скриншота assist-сессии: системный display-кадр против фолбэка нашего захвата.
 * Вызывается, только когда системный кадр почти чёрный. Кадр никогда не выбрасывается
 * (тёмное содержимое может быть легитимным) — всегда либо замена на более чистый кадр,
 * либо пометка при кадре как есть. Пометка не противоречит приложенному кадру:
 * «почти чёрный» утверждается только когда приложенный кадр и правда почти чёрный —
 * системный или переснятый фолбэком.
 */
internal fun chooseAssistScreenshot(
    systemImage: ImagePart,
    fallback: UiActions.QuickShot?,
): AssistScreenshotChoice {
    val image = fallback?.image ?: systemImage
    val note = when {
        fallback == null -> BLACK_NOTE
        // Оконный захват не содержит чужие оверлеи — чёрный кадр уже не шторка.
        fallback.black -> BLACK_NOTE
        // Контраст «весь экран чёрный, окно не чёрное» — разница и есть чужой оверлей.
        fallback.windowCapture -> REPLACED_NOTE
        // Пересняли без контраста display/окно (API ≤ 33 или окно не найдено) — без уверенных причин.
        else -> RESHOT_NOTE
    }
    return AssistScreenshotChoice(image, note)
}

internal const val REPLACED_NOTE =
    "Скриншот переснят через сервис доступности: системный кадр был скрыт шторкой экранного " +
        "чтеца (или другим непрозрачным оверлеем)."

internal const val RESHOT_NOTE =
    "Системный скриншот был почти чёрным — переснят через сервис доступности, приложен свежий кадр."

internal const val BLACK_NOTE =
    "Скриншот почти чёрный: возможно, тёмное содержимое или защищённое окно; возможно, экран " +
        "скрыт шторкой экранного чтеца (её нельзя отключить извне). Кадр приложен без обработки; " +
        "если нужен видимый снимок — попроси пользователя временно снять шторку жестом " +
        "экранного чтеца."

/**
 * Координатор скриншота assist-сессии при чёрном системном кадре: держит состояние
 * «фолбэк в полёте» и гарантирует, что будет отдано ровно одно изображение —
 * результат фолбэка либо отложенный системный кадр на дедлайне. Запоздавший результат
 * фолбэка игнорируется (сессия могла закрыться по дедлайну). Все вызовы — с главного потока.
 */
internal class AssistScreenshotCoordinator(
    private val deliver: (image: ImagePart, note: String?) -> Unit,
) {
    private var pending = false
    private var held: ImagePart? = null

    val hasPendingFallback: Boolean get() = pending

    /**
     * Чёрный системный кадр: отложить его и уйти в фолбэк.
     * false — фолбэк уже идёт (второго колбэка снимка за показ не бывает).
     */
    fun holdForFallback(blackImage: ImagePart): Boolean {
        if (pending) return false
        pending = true
        held = blackImage
        return true
    }

    /** Итог фолбэка (null — фолбэк не удался). После дедлайна или без hold — игнорируется. */
    fun onFallbackResult(fallback: UiActions.QuickShot?) {
        if (!pending) return
        pending = false
        val blackImage = held ?: return
        held = null
        val choice = chooseAssistScreenshot(blackImage, fallback)
        deliver(choice.image, choice.note)
    }

    /** Дедлайн истёк при висящем фолбэке — отдать системный кадр с честной пометкой. */
    fun onDeadline() {
        if (!pending) return
        pending = false
        val blackImage = held ?: return
        held = null
        deliver(blackImage, BLACK_NOTE)
    }
}
