package ru.outsideproarts.oassist.core.agent

/**
 * Activity, отвечающая на SET_ALARM/SET_TIMER. Обработчиков для двух действий
 * набирается отдельно — наличие SET_ALARM у пакета не гарантирует SET_TIMER.
 */
data class ClockHandler(
    val packageName: String,
    val label: String,
)

private const val CLOCK_HANDLERS_MAX = 10

/**
 * Ошибка без найденного обработчика. [requestedPackage] — пакет, который модель
 * передала явно и который не умеет нужное действие (null — обработчиков нет вовсе).
 * Список отдаём в ошибке, а не в описании тула: discovery по [formatClockHandlers]
 * не расходует токены на каждый запрос.
 */
fun noClockHandlerError(
    action: String,
    requestedPackage: String?,
    handlers: List<ClockHandler>,
): String {
    val list = formatClockHandlers(handlers)
    return when {
        requestedPackage != null ->
            "Приложение '$requestedPackage' не умеет $action. Доступные приложения: $list"
        handlers.isEmpty() ->
            "Не найдено приложение часов, которое умеет $action. Установите приложение часов и повторите; " +
                "будильник не создан."
        else ->
            "Не удалось найти обработчик для $action. Доступные приложения: $list"
    }
}

/**
 * Список обработчиков одной строкой для текста ошибки модели.
 * Кап — по [CLOCK_HANDLERS_MAX] пакетов, дальше «…».
 */
fun formatClockHandlers(handlers: List<ClockHandler>): String {
    if (handlers.isEmpty()) return "нет"
    val shown = handlers.take(CLOCK_HANDLERS_MAX)
    val names = shown.joinToString(", ") { "«${it.label}» (${it.packageName})" }
    return if (handlers.size > shown.size) "$names, … (${handlers.size - shown.size} ещё)" else names
}
