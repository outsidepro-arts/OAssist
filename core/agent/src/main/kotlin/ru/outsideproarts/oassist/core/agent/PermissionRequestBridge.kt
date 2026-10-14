package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Итог запроса системных разрешений. */
enum class PermissionRequestOutcome {
    /** Все разрешения выданы. */
    GRANTED,

    /** Пользователь отклонил хотя бы одно; системный диалог ещё может появиться снова. */
    DENIED,

    /** Отказ с «больше не спрашивать»: системный диалог больше не появится. */
    DENIED_FOREVER,

    /** Показать системный диалог не удалось (старт окна заблокирован, таймаут, смерть активности). */
    UNAVAILABLE,
}

/**
 * Запрос разрешений, чей системный диалог не смог показаться (типично для notification-
 * режима из фона: Android блокирует старт активности молча, без исключения).
 * Состояние для UI-фолбэка: действие «Выдать» в сессионном уведомлении (PendingIntent
 * на ConsentActivity) — легальный способ показать диалог по тапу пользователя.
 */
data class BlockedPermissionRequest(
    val requestId: String,
    val permissions: List<String>,
)

/**
 * Мост «запрос разрешений ↔ UI»: инициатор (AgentLoop через [PermissionRequester])
 * регистрирует ожидание и стартует `ConsentActivity`; активность ставит метку
 * [markStarted] и завершает ожидание через [complete] результатом системного диалога.
 *
 * [await] — fail-safe против вечных зависаний: окно должно появиться за [START_TIMEOUT_MS],
 * иначе запрос публикуется в [blocked] (фолбэк-действие в уведомлении) и ожидание
 * продолжается ещё [USER_TIMEOUT_MS] на ответ пользователя (в том числе после тапа
 * по фолбэку). По таймауту ожидание снимается с [PermissionRequestOutcome.UNAVAILABLE].
 */
object PermissionRequestBridge {

    /** Окно должно показаться быстро; дальше — либо диалог, либо фолбэк в уведомлении. */
    const val START_TIMEOUT_MS = 5_000L

    /** Сколько ждать ответа пользователя: диалог можно не торопить. */
    const val USER_TIMEOUT_MS = 180_000L

    private class Pending(val permissions: List<String>) {
        val outcome = CompletableDeferred<PermissionRequestOutcome>()
        val started = CompletableDeferred<Unit>()
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val _blocked = MutableStateFlow<BlockedPermissionRequest?>(null)

    /** Активный запрос, чей системный диалог заблокирован; `null` — такого нет. */
    val blocked: StateFlow<BlockedPermissionRequest?> = _blocked.asStateFlow()

    fun register(permissions: List<String>): String {
        val id = UUID.randomUUID().toString()
        pending[id] = Pending(permissions)
        return id
    }

    /** `ConsentActivity` показана: окно стартовало, фолбэк-действие больше не нужно. */
    fun markStarted(id: String) {
        clearBlocked(id)
        pending[id]?.started?.complete(Unit)
    }

    fun complete(id: String, outcome: PermissionRequestOutcome) {
        clearBlocked(id)
        pending.remove(id)?.outcome?.complete(outcome)
    }

    /** Отмена/пропажа активности: снять ожидание, чтобы не висело. */
    fun cancel(id: String) {
        clearBlocked(id)
        pending.remove(id)?.outcome?.complete(PermissionRequestOutcome.UNAVAILABLE)
    }

    /**
     * Ожидание результата системного диалога: сначала старт окна ([startTimeoutMs]),
     * затем ответ пользователя ([userTimeoutMs]). Провал старта публикует [blocked]
     * и не обрывает ожидание — пользователь может тапнуть фолбэк-действие в уведомлении.
     */
    suspend fun await(
        id: String,
        startTimeoutMs: Long = START_TIMEOUT_MS,
        userTimeoutMs: Long = USER_TIMEOUT_MS,
    ): PermissionRequestOutcome {
        val p = pending[id] ?: return PermissionRequestOutcome.UNAVAILABLE
        val started = withTimeoutOrNull(startTimeoutMs) { p.started.await() } != null
        if (!started && !p.outcome.isCompleted) {
            _blocked.value = BlockedPermissionRequest(id, p.permissions)
        }
        val outcome = withTimeoutOrNull(userTimeoutMs) { p.outcome.await() }
        if (outcome == null) {
            cancel(id)
            return PermissionRequestOutcome.UNAVAILABLE
        }
        return outcome
    }

    private fun clearBlocked(id: String) {
        val current = _blocked.value
        if (current?.requestId == id) _blocked.compareAndSet(current, null)
    }
}
