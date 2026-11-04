package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * служебные — всегда ALLOW; актуальный DENY; «на сессию» → ALLOW; дефолт — ASK.
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

    @Synchronized
    override fun state(groupKey: String, scope: String): ApprovalState = when {
        ApprovalStates.stateOf(remembered, groupKey) == ApprovalState.DENY -> ApprovalState.DENY
        sessionAllowed[scope].orEmpty().contains(groupKey) -> ApprovalState.ALLOW
        else -> ApprovalStates.stateOf(remembered, groupKey)
    }

    @Synchronized
    override fun setRemembered(groupKey: String, state: ApprovalState) {
        sessionAllowed.values.forEach { grants ->
            if (groupKey == ToolGroups.MCP_DEFAULT) grants.removeAll { ToolGroups.isMcp(it) }
            else grants.remove(groupKey)
        }
        if (state == ApprovalState.ASK) remembered.remove(groupKey) else remembered[groupKey] = state
    }

    @Synchronized
    override fun setSessionAllow(groupKey: String, scope: String) {
        sessionAllowed.getOrPut(scope) { ConcurrentHashMap.newKeySet() }.add(groupKey)
    }

    @Synchronized
    override fun clearSession(scope: String) {
        sessionAllowed.remove(scope)
    }

    @Synchronized
    override fun clearRemembered() {
        sessionAllowed.clear()
        remembered.clear()
    }

    @Synchronized
    override fun applyPreset(preset: AskPreset) {
        sessionAllowed.clear()
        remembered.clear()
        remembered.putAll(ApprovalStates.applyPreset(preset))
    }

    @Synchronized
    override fun matchesPreset(): AskPreset? = ApprovalStates.matchesPreset(remembered)

    @Synchronized
    override fun remembered(): Map<String, ApprovalState> = remembered.toMap()
}

/**
 * Process-owned policy. Local mutations stay effective until persisted; stale Flow payloads
 * only trigger a fresh read serialized with writes. Failed mutations remain queued for retry.
 */
class PersistentApprovalPolicy(
    private val store: ApprovalStore,
    scope: CoroutineScope,
    initial: Map<String, ApprovalState> = emptyMap(),
) : ApprovalPolicy {
    private val lock = Any()
    private val ioMutex = Mutex()
    private var snapshot = initial.toMap()
    private val sessionAllowed = mutableMapOf<String, MutableSet<String>>()
    private val pending = ArrayDeque<Change>()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)
    private val retryReads = Channel<Unit>(Channel.CONFLATED)
    private var readFailed = false
    private var writeFailed = false
    private val _persistenceFailed = MutableStateFlow(false)
    val persistenceFailed: StateFlow<Boolean> = _persistenceFailed.asStateFlow()

    private class Change(
        val apply: (Map<String, ApprovalState>) -> Map<String, ApprovalState>,
        val write: suspend () -> Unit,
    )

    init {
        scope.launch {
            while (true) {
                try {
                    store.remembered.collect {
                        ioMutex.withLock {
                            val current = store.snapshot()
                            synchronized(lock) {
                                val next = pending.fold(current) { states, change -> change.apply(states) }
                                if (snapshot != next) sessionAllowed.clear()
                                snapshot = next
                                readFailed = false
                                _persistenceFailed.value = writeFailed
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    synchronized(lock) {
                        readFailed = true
                        _persistenceFailed.value = true
                    }
                }
                retryReads.receive()
            }
        }
        scope.launch {
            for (signal in wakeWriter) {
                try {
                    ioMutex.withLock {
                        while (true) {
                            val change = synchronized(lock) { pending.firstOrNull() } ?: break
                            change.write()
                            val current = store.snapshot()
                            synchronized(lock) {
                                pending.removeFirst()
                                snapshot = pending.fold(current) { states, queued -> queued.apply(states) }
                            }
                        }
                        val current = store.snapshot()
                        synchronized(lock) {
                            snapshot = pending.fold(current) { states, queued -> queued.apply(states) }
                        }
                        synchronized(lock) {
                            writeFailed = false
                            _persistenceFailed.value = readFailed
                        }
                        // A successful write must also restart an observer stopped by a read error.
                        retryReads.trySend(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Never roll a failed DENY back to the previously persisted ALLOW.
                    synchronized(lock) {
                        writeFailed = true
                        _persistenceFailed.value = true
                    }
                }
            }
        }
    }

    override fun state(groupKey: String, scope: String): ApprovalState = synchronized(lock) {
        val remembered = ApprovalStates.stateOf(snapshot, groupKey)
        when {
            remembered == ApprovalState.DENY -> ApprovalState.DENY
            groupKey in sessionAllowed[scope].orEmpty() -> ApprovalState.ALLOW
            else -> remembered
        }
    }

    private fun enqueue(change: Change, groupKey: String? = null) = synchronized(lock) {
        if (groupKey == null) sessionAllowed.clear()
        else sessionAllowed.values.forEach { grants ->
            if (groupKey == ToolGroups.MCP_DEFAULT) grants.removeAll { ToolGroups.isMcp(it) }
            else grants.remove(groupKey)
        }
        snapshot = change.apply(snapshot)
        pending.addLast(change)
        wakeWriter.trySend(Unit)
        Unit
    }

    override fun setRemembered(groupKey: String, state: ApprovalState) = enqueue(
        Change(
            { if (state == ApprovalState.ASK) it - groupKey else it + (groupKey to state) },
            { store.setRemembered(groupKey, state) },
        ), groupKey,
    )

    override fun setSessionAllow(groupKey: String, scope: String) = synchronized(lock) {
        sessionAllowed.getOrPut(scope) { mutableSetOf() }.add(groupKey)
        Unit
    }

    override fun clearSession(scope: String) = synchronized(lock) {
        sessionAllowed.remove(scope)
        Unit
    }

    override fun clearRemembered() = enqueue(Change({ emptyMap() }, { store.clear() }))

    override fun applyPreset(preset: AskPreset) {
        val states = ApprovalStates.applyPreset(preset)
        enqueue(Change({ states }, { store.replaceAll(states) }))
    }

    fun retryPersistence() {
        retryReads.trySend(Unit)
        wakeWriter.trySend(Unit)
    }

    override fun matchesPreset(): AskPreset? = synchronized(lock) { ApprovalStates.matchesPreset(snapshot) }
    override fun remembered(): Map<String, ApprovalState> = synchronized(lock) { snapshot.toMap() }
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
