package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.prefs.ApprovalStore
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.AskPreset
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

private class FakeApprovalStore(initial: Map<String, ApprovalState> = emptyMap()) : ApprovalStore {
    private val state = MutableStateFlow(initial)
    val writes = mutableListOf<Pair<String, ApprovalState>>()
    var cleared = false

    override val remembered: Flow<Map<String, ApprovalState>> = state
    override suspend fun snapshot(): Map<String, ApprovalState> = state.value
    override suspend fun setRemembered(groupKey: String, state: ApprovalState) {
        writes.add(groupKey to state)
        this.state.value = if (state == ApprovalState.ASK) {
            this.state.value - groupKey
        } else {
            this.state.value + (groupKey to state)
        }
    }

    override suspend fun replaceAll(states: Map<String, ApprovalState>) {
        state.value = states
    }

    override suspend fun clear() {
        cleared = true
        state.value = emptyMap()
    }
}

class PersistentApprovalPolicyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val smsGroup = ToolGroups.key(ToolCategory.COMMUNICATION, ToolAccess.WRITE)
    private val deviceReadGroup = ToolGroups.key(ToolCategory.DEVICE, ToolAccess.READ)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `запомненное состояние действует на всю группу`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.setRemembered(smsGroup, ApprovalState.ALLOW)
        assertEquals(ApprovalState.ALLOW, policy.state(smsGroup, "CHAT"))
        // Другая группа не затронута.
        assertEquals(ApprovalState.ASK, policy.state(deviceReadGroup, "CHAT"))
    }

    @Test
    fun `сброс возвращает вопрос`() {
        val store = FakeApprovalStore(mapOf(smsGroup to ApprovalState.DENY))
        val policy = PersistentApprovalPolicy(store, scope, initial = mapOf(smsGroup to ApprovalState.DENY))
        assertEquals(ApprovalState.DENY, policy.state(smsGroup, "CHAT"))
        policy.setRemembered(smsGroup, ApprovalState.ASK)
        assertEquals(ApprovalState.ASK, policy.state(smsGroup, "CHAT"))
    }

    @Test
    fun `общий сброс очищает всё`() {
        val store = FakeApprovalStore(mapOf(smsGroup to ApprovalState.DENY))
        val policy = PersistentApprovalPolicy(store, scope, initial = mapOf(smsGroup to ApprovalState.DENY))
        policy.clearRemembered()
        assertEquals(ApprovalState.ASK, policy.state(smsGroup, "CHAT"))
        assertTrue(policy.remembered().isEmpty())
    }

    @Test
    fun `служебная группа всегда разрешена`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        assertEquals(ApprovalState.ALLOW, policy.state(ToolGroups.SERVICE, "CHAT"))
    }

    @Test
    fun `сессионное разрешение живёт до конца сессии`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.setSessionAllow(smsGroup, "CHAT")
        assertEquals(ApprovalState.ALLOW, policy.state(smsGroup, "CHAT"))
        // Другая сессия (ASSISTANT) не затронута.
        assertEquals(ApprovalState.ASK, policy.state(smsGroup, "ASSISTANT"))
        policy.clearSession("CHAT")
        assertEquals(ApprovalState.ASK, policy.state(smsGroup, "CHAT"))
    }

    @Test
    fun `пресет ASK_BOTH совпадает с дефолтом`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        assertEquals(AskPreset.ASK_BOTH, policy.matchesPreset())
    }

    @Test
    fun `пресет пишет состояния категорий и определяется обратно`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.applyPreset(AskPreset.ASK_WRITE)
        // Чтение разрешено, запись спрашивается.
        assertEquals(ApprovalState.ALLOW, policy.state(deviceReadGroup, "CHAT"))
        assertEquals(ApprovalState.ASK, policy.state(smsGroup, "CHAT"))
        assertEquals(AskPreset.ASK_WRITE, policy.matchesPreset())
    }

    @Test
    fun `ручная правка категории даёт Настраивается вручную`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.applyPreset(AskPreset.ASK_BOTH)
        policy.setRemembered(smsGroup, ApprovalState.DENY)
        assertNull(policy.matchesPreset())
    }

    @Test
    fun `Запретить все покрывает и MCP`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.applyPreset(AskPreset.DENY_ALL)
        assertEquals(ApprovalState.DENY, policy.state(ToolGroups.mcp("srv-1"), "CHAT"))
        assertEquals(AskPreset.DENY_ALL, policy.matchesPreset())
    }

    @Test
    fun `MCP-группа спрашивает вне явных пресетов`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.applyPreset(AskPreset.ASK_READ)
        assertEquals(ApprovalState.ASK, policy.state(ToolGroups.mcp("srv-1"), "CHAT"))
    }
}
