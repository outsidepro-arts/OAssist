package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.delay

/**
 * Нотации записей компонент в secure-настройках `enabled_accessibility_services` и
 * `enabled_notification_listeners`: платформа пишет и `pkg/.Class` (короткая), и
 * `pkg/pkg.Class` (полная) — в зависимости от версии/OEM. Сравнение строк в одной
 * нотации ломается на другой (сервис «выключен» при реально включённом), поэтому
 * обе сводятся к полной через [normalizeServiceComponent].
 */

/** Нормализация записи компоненты: `pkg/.Class` → `pkg/pkg.Class`; прочее — как есть. */
fun normalizeServiceComponent(entry: String): String {
    val trimmed = entry.trim()
    val sep = trimmed.indexOf('/')
    if (sep <= 0 || sep == trimmed.lastIndex) return trimmed
    val pkg = trimmed.substring(0, sep)
    val cls = trimmed.substring(sep + 1)
    val full = if (cls.startsWith('.')) pkg + cls else cls
    return "$pkg/$full"
}

/** Содержит ли `:`-список включённых сервисов компоненту (обе нотации равнозначны). */
fun enabledServicesContain(services: String?, component: String): Boolean {
    if (services.isNullOrBlank()) return false
    val target = normalizeServiceComponent(component)
    return services.split(':').any { it.isNotBlank() && normalizeServiceComponent(it) == target }
}

/**
 * Ожидание переподключения сервиса после [requestReconnect]; null — не подключился
 * за отведённое время. Каждая попытка опроса — [pollMs], потолок — [timeoutMs].
 */
suspend fun <T : Any> awaitReconnected(
    timeoutMs: Long,
    pollMs: Long,
    requestReconnect: suspend () -> Unit,
    provider: () -> T?,
): T? {
    runCatching { requestReconnect() }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        delay(pollMs.coerceAtLeast(1L))
        provider()?.let { return it }
    }
    return null
}
