package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.outsideproarts.oassist.core.device.AccessibilityReassertUnavailableException

class AccessibilityRestoreException(cause: Throwable) : IllegalStateException("Accessibility reassert failed", cause)

suspend fun requestAccessibilityReassert(request: suspend () -> Result<Unit>) {
    request().getOrElse {
        currentCoroutineContext().ensureActive()
        if (it !is AccessibilityReassertUnavailableException) throw AccessibilityRestoreException(it)
    }
}

/**
 * Нотации записей компонент в secure-настройках `enabled_accessibility_services` и
 * `enabled_notification_listeners`: платформа пишет и `pkg/.Class` (короткая), и
 * `pkg/pkg.Class` (полная) — в зависимости от версии/OEM. Сравнение строк в одной
 * нотации ломается на другой (сервис «выключен» при реально включённом), поэтому
 * обе сводятся к полной через [normalizeServiceComponent].
 */

/** Нормализация записи компоненты: `pkg/.Class` → `pkg/pkg.Class`; прочее — как есть. */
fun normalizeServiceComponent(entry: String): String =
    ru.outsideproarts.oassist.core.device.normalizeServiceComponent(entry)

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
    try {
        requestReconnect()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AccessibilityRestoreException) {
        throw e
    } catch (_: Exception) {
        // A failed reconnect request still permits the service to connect independently.
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        delay(pollMs.coerceAtLeast(1L))
        provider()?.let { return it }
    }
    return null
}
