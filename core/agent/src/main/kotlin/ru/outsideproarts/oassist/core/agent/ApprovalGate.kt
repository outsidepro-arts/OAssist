package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import java.util.concurrent.ConcurrentHashMap

/**
 * Политика автоподтверждений: какие инструменты выполняются без паузы.
 * In-memory на M2; персист per-tool решений — в DataStore (Фаза 1.3).
 */
interface ApprovalPolicy {
    /** `true` — выполнить без запроса; `ASK`/`ALWAYS_ASK` без записи — через диалог. */
    fun shouldAutoApprove(toolName: String, risk: ActionRisk): Boolean

    /** Запомнить/убрать «всегда разрешать» для инструмента. */
    fun setAlwaysAllow(toolName: String, allow: Boolean)

    /** Убрать все записи «всегда разрешать» (кнопка сброса в настройках). */
    fun clearAlwaysAllowed()
}

/** Дефолт: `AUTO` — без спроса, остальное — спрашивать, если нет записи «всегда». */
class InMemoryApprovalPolicy : ApprovalPolicy {
    private val alwaysAllowed = ConcurrentHashMap.newKeySet<String>()

    override fun shouldAutoApprove(toolName: String, risk: ActionRisk): Boolean =
        when (risk) {
            ActionRisk.AUTO -> true
            ActionRisk.ASK, ActionRisk.ALWAYS_ASK -> toolName in alwaysAllowed
        }

    override fun setAlwaysAllow(toolName: String, allow: Boolean) {
        if (allow) alwaysAllowed.add(toolName) else alwaysAllowed.remove(toolName)
    }

    override fun clearAlwaysAllowed() {
        alwaysAllowed.clear()
    }
}

/**
 * Мост «пауза AgentLoop ↔ диалог UI».
 * Цикл регистрирует ожидание и suspend'ится в [awaitDecision];
 * UI отвечает через [resolve]. Неразрешённое ожидание висит до отмены коллектора.
 */
class ApprovalController {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()

    /**
     * Предсоздать ожидание до эмита [ChatChunk.ApprovalNeeded]: иначе быстрый
     * ответ UI между `emit` и [awaitDecision] потеряется и стрим зависнет.
     */
    fun prepare(callId: String) {
        pending.getOrPut(callId) { CompletableDeferred() }
    }

    suspend fun awaitDecision(callId: String): ApprovalDecision {
        val deferred = pending.getOrPut(callId) { CompletableDeferred() }
        return try {
            deferred.await()
        } finally {
            pending.remove(callId)
        }
    }

    /** `false` — ожидания с таким id нет (двойной ответ или гонка). */
    fun resolve(callId: String, decision: ApprovalDecision): Boolean =
        pending[callId]?.complete(decision) ?: false

    fun hasPending(callId: String): Boolean = pending.containsKey(callId)
}
