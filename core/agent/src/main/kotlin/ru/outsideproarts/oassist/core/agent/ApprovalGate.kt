package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.data.prefs.ApprovalStore
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
 * Персистентная политика (M5.3): записи «всегда разрешать» живут в DataStore.
 * In-memory снимок для синхронного [shouldAutoApprove]; стор — источник истины,
 * коллектор в [scope] держит снимок свежим. Запись идёт в обе стороны сразу,
 * чтобы диалог «Всегда» работал без ожидания эмита Flow.
 */
class PersistentApprovalPolicy(
    private val store: ApprovalStore,
    scope: CoroutineScope,
    initial: Set<String> = emptySet(),
) : ApprovalPolicy {
    @Volatile
    private var snapshot: Set<String> = initial

    init {
        scope.launch {
            store.alwaysAllowed.collect { snapshot = it }
        }
        scope.launch {
            snapshot = runCatching { store.snapshot() }.getOrDefault(snapshot)
        }
    }

    override fun shouldAutoApprove(toolName: String, risk: ActionRisk): Boolean =
        when (risk) {
            ActionRisk.AUTO -> true
            ActionRisk.ASK, ActionRisk.ALWAYS_ASK -> toolName in snapshot
        }

    override fun setAlwaysAllow(toolName: String, allow: Boolean) {
        snapshot = if (allow) snapshot + toolName else snapshot - toolName
        pendingWrite(store, toolName, allow)
    }

    override fun clearAlwaysAllowed() {
        snapshot = emptySet()
        pendingClear(store)
    }

    companion object {
        // Записи — через shared IO-скоуп, без привязки к жизненному циклу экрана.
        private val writeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        private fun pendingWrite(store: ApprovalStore, toolName: String, allow: Boolean) {
            writeScope.launch { runCatching { store.setAlwaysAllow(toolName, allow) } }
        }

        private fun pendingClear(store: ApprovalStore) {
            writeScope.launch { runCatching { store.clear() } }
        }
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
