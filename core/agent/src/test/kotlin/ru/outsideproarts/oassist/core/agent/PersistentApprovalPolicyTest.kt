package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.prefs.ApprovalStore
import ru.outsideproarts.oassist.core.model.ActionRisk

private class FakeApprovalStore(initial: Set<String> = emptySet()) : ApprovalStore {
    private val state = MutableStateFlow(initial)
    val writes = mutableListOf<Pair<String, Boolean>>()
    var cleared = false

    override val alwaysAllowed: Flow<Set<String>> = state
    override suspend fun snapshot(): Set<String> = state.value
    override suspend fun setAlwaysAllow(toolName: String, allow: Boolean) {
        writes.add(toolName to allow)
        state.value = if (allow) state.value + toolName else state.value - toolName
    }
    override suspend fun replaceAll(toolNames: Set<String>) {
        state.value = toolNames
    }
    override suspend fun clear() {
        cleared = true
        state.value = emptySet()
    }
}

class PersistentApprovalPolicyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `запись сразу действует без ожидания стора`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        policy.setAlwaysAllow("sms_send", true)
        assertTrue(policy.shouldAutoApprove("sms_send", ActionRisk.ASK))
    }

    @Test
    fun `сброс убирает все разрешения`() {
        val store = FakeApprovalStore(setOf("sms_send"))
        val policy = PersistentApprovalPolicy(store, scope, initial = setOf("sms_send"))
        assertTrue(policy.shouldAutoApprove("sms_send", ActionRisk.ASK))
        policy.clearAlwaysAllowed()
        assertFalse(policy.shouldAutoApprove("sms_send", ActionRisk.ASK))
    }

    @Test
    fun `AUTO всегда без спроса`() {
        val store = FakeApprovalStore()
        val policy = PersistentApprovalPolicy(store, scope)
        assertTrue(policy.shouldAutoApprove("device_info", ActionRisk.AUTO))
    }
}
