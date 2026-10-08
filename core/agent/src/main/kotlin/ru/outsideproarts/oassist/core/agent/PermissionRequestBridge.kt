package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Итог запроса системных разрешений. */
enum class PermissionRequestOutcome {
    /** Все разрешения выданы. */
    GRANTED,

    /** Пользователь отклонил хотя бы одно. */
    DENIED,

    /** Показать системный диалог не удалось (например, из фона без окна). */
    UNAVAILABLE,
}

/**
 * Мост «запрос разрешений ↔ UI»: инициатор (AgentLoop через [PermissionRequester])
 * регистрирует ожидание и стартует `ConsentActivity`; активность завершает его
 * через [complete] результатом системного диалога.
 */
object PermissionRequestBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PermissionRequestOutcome>>()

    fun register(): Pair<String, CompletableDeferred<PermissionRequestOutcome>> {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<PermissionRequestOutcome>()
        pending[id] = deferred
        return id to deferred
    }

    fun complete(id: String, outcome: PermissionRequestOutcome) {
        pending.remove(id)?.complete(outcome)
    }

    /** Отмена/пропажа активности: снять ожидание, чтобы не висело. */
    fun cancel(id: String) {
        pending.remove(id)?.complete(PermissionRequestOutcome.UNAVAILABLE)
    }
}
