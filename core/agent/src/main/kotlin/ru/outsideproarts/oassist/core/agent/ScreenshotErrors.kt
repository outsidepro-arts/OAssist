package ru.outsideproarts.oassist.core.agent

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.delay

/** Ошибка захвата экрана с кодом `AccessibilityService.TakeScreenshotCallback`. */
internal class ScreenshotException(val code: Int) : Exception("screenshot error $code")

/**
 * Платформа (AOSP `AbstractAccessibilityServiceConnection`) не отдаёт два снимка чаще,
 * чем раз в 333 мс (семантика сравнения «<=»). Метку времени обновляет запрос, прошедший
 * эту проверку, — в том числе упавший позже (нет доступа, secure-окно); отбитый по
 * троттлингу запрос окно не двигает. Повтор всё равно ждёт с запасом от неудачной попытки —
 * так покрыты обе схемы.
 */
internal const val SCREENSHOT_RETRY_DELAY_MS = 500L
internal const val SCREENSHOT_RETRY_MAX = 2

/**
 * Повторяет захват только при троттлинге платформы
 * (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`): ошибка временная и лечится ожиданием;
 * остальные коды (secure-окно, протухшее окно) повтор не лечит.
 */
internal suspend fun <T> withScreenshotRetry(
    maxRetries: Int = SCREENSHOT_RETRY_MAX,
    delayMs: Long = SCREENSHOT_RETRY_DELAY_MS,
    wait: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> Result<T>,
): Result<T> {
    var retries = 0
    while (true) {
        val result = block()
        val throttled = (result.exceptionOrNull() as? ScreenshotException)?.code ==
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        if (!throttled || retries >= maxRetries) return result
        retries++
        wait(delayMs)
    }
}

/** Человекочитаемое сообщение по коду ошибки захвата; null — код неизвестен. */
internal fun screenshotErrorMessage(code: Int): String? = when (code) {
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
        "Не удалось сделать снимок — внутренняя ошибка платформы; повтори запрос"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
        "Сервис доступности потерял доступ к захвату экрана — откройте приложение OAssist, " +
            "подключение восстановится, и повтори снимок"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
        "Слишком частые запросы снимков — повтори через секунду"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
        "Экран для снимка недоступен — повтори запрос"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
        "Окно для снимка исчезло — вызови ui_get_screen и повтори снимок"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
        "Окно защищено от скриншотов (FLAG_SECURE) — снимок невозможен"
    else -> null
}
