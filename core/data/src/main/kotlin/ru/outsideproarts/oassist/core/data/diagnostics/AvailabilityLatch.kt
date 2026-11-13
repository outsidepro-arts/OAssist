package ru.outsideproarts.oassist.core.data.diagnostics

/**
 * Проба доступности с кэшем успеха: пока [probe] возвращает `false`, вызовы
 * повторяются (хранилище может стать доступным после разблокировки пользователя),
 * но первый успех запоминается навсегда — доступность теряется только со
 * сменой пользователя. Нужна, чтобы binder-проба (`UserManager.isUserUnlocked`)
 * не выполнялась на каждое событие диагностики: record живёт на горячем пути
 * и вызывается, в том числе, с главного потока.
 */
internal class AvailabilityLatch(private val probe: () -> Boolean) {
    @Volatile
    private var unlocked = false

    operator fun invoke(): Boolean {
        if (unlocked) return true
        val available = probe()
        if (available) unlocked = true
        return available
    }
}
