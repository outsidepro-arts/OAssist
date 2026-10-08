package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.data.prefs.ApprovalStore
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.AskPreset
import ru.outsideproarts.oassist.core.model.ToolGroups
import java.util.concurrent.ConcurrentHashMap

/**
 * Политика подтверждений: состояния групп разрешений (спрашивать/разрешено/запретить).
 * Решение пользователя действует на всю группу (категория × доступ или `mcp:<id>`),
 * а не на отдельный инструмент.
 */
interface ApprovalPolicy {
    /**
     * Эффективное состояние группы для сессии [scope] (CHAT/ASSISTANT/SHARE):
     * служебные — всегда ALLOW; «на сессию» → ALLOW; запомненное; дефолт — ASK.
     */
    fun state(groupKey: String, scope: String): ApprovalState

    /** Запомнить решение группы навсегда; [ApprovalState.ASK] — забыть (спрашивать). */
    fun setRemembered(groupKey: String, state: ApprovalState)

    /** Разрешить группу до конца сессии [scope] (in-memory). */
    fun setSessionAllow(groupKey: String, scope: String)

    /** Закончилась сессия (диалог/панель) — забыть «на текущую сессию». */
    fun clearSession(scope: String)

    /** Сбросить все запомненные решения (кнопка в настройках). */
    fun clearRemembered()

    /** Применить пресет: переписать состояния всех категорий разом. */
    fun applyPreset(preset: AskPreset)

    /**
     * Пресет, которому соответствуют текущие состояния, либо `null` —
     * «Настраивается вручную» (категории меняли вне пресета).
     */
    fun matchesPreset(): AskPreset?

    /** Запомненные состояния (для списка «Запомненные разрешения» в настройках). */
    fun remembered(): Map<String, ApprovalState>
}

/** Чистая логика состояний поверх снимка `groupKey -> state` (общая для реализаций политики). */
internal object ApprovalStates {
    fun stateOf(remembered: Map<String, ApprovalState>, groupKey: String): ApprovalState {
        if (groupKey == ToolGroups.SERVICE) return ApprovalState.ALLOW
        val explicit = remembered[groupKey]
        if (explicit != null) return explicit
        if (ToolGroups.isMcp(groupKey)) {
            remembered[ToolGroups.MCP_DEFAULT]?.let { return it }
        }
        return ApprovalState.ASK
    }

    fun applyPreset(preset: AskPreset): Map<String, ApprovalState> {
        val next = mutableMapOf<String, ApprovalState>()
        ToolGroups.categoryGroups().forEach { group ->
            val state = preset.stateFor(ToolGroups.accessOf(group))
            if (state != ApprovalState.ASK) next[group] = state
        }
        val mcpDefault = preset.stateFor(null)
        if (mcpDefault != ApprovalState.ASK) next[ToolGroups.MCP_DEFAULT] = mcpDefault
        return next
    }

    fun matchesPreset(remembered: Map<String, ApprovalState>): AskPreset? =
        AskPreset.entries.firstOrNull { preset -> matches(remembered, preset) }

    private fun matches(remembered: Map<String, ApprovalState>, preset: AskPreset): Boolean {
        val categoriesOk = ToolGroups.categoryGroups().all { group ->
            (remembered[group] ?: ApprovalState.ASK) == preset.stateFor(ToolGroups.accessOf(group))
        }
        val mcpOverrides = remembered.keys.any {
            ToolGroups.isMcp(it) && it != ToolGroups.MCP_DEFAULT
        }
        val mcpOk = (remembered[ToolGroups.MCP_DEFAULT] ?: ApprovalState.ASK) == preset.stateFor(null) &&
            !mcpOverrides
        return categoriesOk && mcpOk
    }
}

/**
 * In-memory политика: запоминание живёт до перезапуска процесса.
 * Сессии — per-scope ([setSessionAllow]/[clearSession]).
 */
class InMemoryApprovalPolicy : ApprovalPolicy {
    private val remembered = ConcurrentHashMap<String, ApprovalState>()
    private val sessionAllowed = ConcurrentHashMap<String, MutableSet<String>>()

    override fun state(groupKey: String, scope: String): ApprovalState = when {
        sessionAllowed[scope].orEmpty().contains(groupKey) -> ApprovalState.ALLOW
        else -> ApprovalStates.stateOf(remembered, groupKey)
    }

    override fun setRemembered(groupKey: String, state: ApprovalState) {
        if (state == ApprovalState.ASK) remembered.remove(groupKey) else remembered[groupKey] = state
    }

    override fun setSessionAllow(groupKey: String, scope: String) {
        sessionAllowed.getOrPut(scope) { ConcurrentHashMap.newKeySet() }.add(groupKey)
    }

    override fun clearSession(scope: String) {
        sessionAllowed.remove(scope)
    }

    override fun clearRemembered() {
        remembered.clear()
    }

    override fun applyPreset(preset: AskPreset) {
        remembered.clear()
        remembered.putAll(ApprovalStates.applyPreset(preset))
    }

    override fun matchesPreset(): AskPreset? = ApprovalStates.matchesPreset(remembered)

    override fun remembered(): Map<String, ApprovalState> = remembered.toMap()
}

/**
 * Персистентная политика (M5.3 → v2): запомненные состояния групп живут в DataStore.
 * In-memory снимок для синхронного [state]; стор — источник истины. Запись идёт
 * в обе стороны сразу, чтобы диалог работал без ожидания эмита Flow.
 */
class PersistentApprovalPolicy(
    private val store: ApprovalStore,
    scope: CoroutineScope,
    initial: Map<String, ApprovalState> = emptyMap(),
) : ApprovalPolicy {
    @Volatile
    private var snapshot: Map<String, ApprovalState> = initial

    private val sessionAllowed = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        scope.launch {
            store.remembered.collect { snapshot = it }
        }
        scope.launch {
            snapshot = runCatching { store.snapshot() }.getOrDefault(snapshot)
        }
    }

    override fun state(groupKey: String, scope: String): ApprovalState = when {
        sessionAllowed[scope].orEmpty().contains(groupKey) -> ApprovalState.ALLOW
        else -> ApprovalStates.stateOf(snapshot, groupKey)
    }

    override fun setRemembered(groupKey: String, state: ApprovalState) {
        snapshot = if (state == ApprovalState.ASK) {
            snapshot - groupKey
        } else {
            snapshot + (groupKey to state)
        }
        pendingWrite(store, groupKey, state)
    }

    override fun setSessionAllow(groupKey: String, scope: String) {
        sessionAllowed.getOrPut(scope) { ConcurrentHashMap.newKeySet() }.add(groupKey)
    }

    override fun clearSession(scope: String) {
        sessionAllowed.remove(scope)
    }

    override fun clearRemembered() {
        snapshot = emptyMap()
        pendingClear(store)
    }

    override fun applyPreset(preset: AskPreset) {
        snapshot = ApprovalStates.applyPreset(preset)
        pendingReplace(store, snapshot)
    }

    override fun matchesPreset(): AskPreset? = ApprovalStates.matchesPreset(snapshot)

    override fun remembered(): Map<String, ApprovalState> = snapshot

    companion object {
        // Записи — через shared IO-скоуп, без привязки к жизненному циклу экрана.
        private val writeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        private fun pendingWrite(store: ApprovalStore, groupKey: String, state: ApprovalState) {
            writeScope.launch { runCatching { store.setRemembered(groupKey, state) } }
        }

        private fun pendingReplace(store: ApprovalStore, states: Map<String, ApprovalState>) {
            writeScope.launch { runCatching { store.replaceAll(states) } }
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
     * Предсоздать ожидание до эмита `ChatChunk.ApprovalNeeded`: иначе быстрый
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
