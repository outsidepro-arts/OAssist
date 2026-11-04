package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.data.prefs.ApprovalStore
import ru.outsideproarts.oassist.core.model.*

internal class ControlledApprovalStore(initial: Map<String, ApprovalState> = emptyMap()) : ApprovalStore {
    var disk = initial
    override val remembered = MutableSharedFlow<Map<String, ApprovalState>>(replay = 1).apply { tryEmit(initial) }
    var loadBarrier: CompletableDeferred<Unit>? = null
    var writeBarrier: CompletableDeferred<Unit>? = null
    var failWrite = false
    var failNextLoad = false
    val writes = mutableListOf<ApprovalState>()
    override suspend fun snapshot(): Map<String, ApprovalState> {
        if (failNextLoad) {
            failNextLoad = false
            error("read failed")
        }
        val captured = disk
        loadBarrier?.also { loadBarrier = null }?.await()
        return captured
    }
    override suspend fun setRemembered(groupKey: String, state: ApprovalState) {
        writes += state
        writeBarrier?.also { writeBarrier = null }?.await()
        check(!failWrite) { "disk failed" }
        disk = if (state == ApprovalState.ASK) disk - groupKey else disk + (groupKey to state)
        remembered.emit(disk)
    }
    override suspend fun replaceAll(states: Map<String, ApprovalState>) {
        check(!failWrite)
        disk = states
        remembered.emit(disk)
    }
    override suspend fun clear() = replaceAll(emptyMap())
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApprovalPolicyRaceTest {
    private val group = ToolGroups.key(ToolCategory.SCREEN_UI, ToolAccess.WRITE)

    @Test fun delayedLoadAndOldEmissionCannotRestoreAllow() = runTest {
        val store = ControlledApprovalStore(mapOf(group to ApprovalState.ALLOW))
        val load = CompletableDeferred<Unit>()
        store.loadBarrier = load
        val policy = PersistentApprovalPolicy(store, backgroundScope)
        runCurrent()
        policy.setRemembered(group, ApprovalState.DENY)
        assertEquals(ApprovalState.DENY, policy.state(group, "CHAT"))
        load.complete(Unit)
        runCurrent()
        store.remembered.emit(mapOf(group to ApprovalState.ALLOW))
        runCurrent()
        assertEquals(ApprovalState.DENY, policy.state(group, "CHAT"))
        val reopened = PersistentApprovalPolicy(store, backgroundScope)
        runCurrent()
        assertEquals(ApprovalState.DENY, reopened.state(group, "CHAT"))
    }

    @Test fun delayedAllowWriteCannotOvertakeDenyAndFailureRetainsDenyUntilRetry() = runTest {
        val store = ControlledApprovalStore()
        val policy = PersistentApprovalPolicy(store, backgroundScope)
        runCurrent()
        val write = CompletableDeferred<Unit>()
        store.writeBarrier = write
        policy.setRemembered(group, ApprovalState.ALLOW)
        runCurrent()
        policy.setRemembered(group, ApprovalState.DENY)
        runCurrent()
        assertEquals(listOf(ApprovalState.ALLOW), store.writes)
        assertEquals(ApprovalState.DENY, policy.state(group, "CHAT"))
        store.failWrite = true
        write.complete(Unit)
        runCurrent()
        assertTrue(policy.persistenceFailed.value)
        assertEquals(ApprovalState.DENY, policy.state(group, "CHAT"))
        store.failWrite = false
        policy.retryPersistence()
        runCurrent()
        assertFalse(policy.persistenceFailed.value)
        assertEquals(ApprovalState.DENY, store.disk[group])
        val reopened = PersistentApprovalPolicy(store, backgroundScope)
        runCurrent()
        assertEquals(ApprovalState.DENY, reopened.state(group, "CHAT"))
    }

    @Test fun cancelledOwnerDoesNotFinishQueuedWrites() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = ControlledApprovalStore()
        val policy = PersistentApprovalPolicy(store, owner)
        runCurrent()
        val write = CompletableDeferred<Unit>()
        store.writeBarrier = write
        policy.setRemembered(group, ApprovalState.ALLOW)
        runCurrent()
        policy.setRemembered(group, ApprovalState.DENY)
        owner.cancel()
        write.complete(Unit)
        runCurrent()
        assertTrue(store.disk.isEmpty())
        assertEquals(ApprovalState.DENY, policy.state(group, "CHAT"))
    }

    @Test fun presetsAndExplicitResetsRevokeSessionGrantsInBothPolicies() = runTest {
        for (policy in listOf(InMemoryApprovalPolicy(), PersistentApprovalPolicy(ControlledApprovalStore(), backgroundScope))) {
            for (key in listOf(group, ToolGroups.mcp("server"))) {
                policy.setSessionAllow(key, "CHAT")
                policy.applyPreset(AskPreset.DENY_ALL)
                assertEquals(ApprovalState.DENY, policy.state(key, "CHAT"))
                policy.setSessionAllow(key, "CHAT")
                assertEquals(ApprovalState.DENY, policy.state(key, "CHAT"))
                policy.applyPreset(AskPreset.ASK_BOTH)
                assertEquals(ApprovalState.ASK, policy.state(key, "CHAT"))
                policy.setSessionAllow(key, "CHAT")
                policy.setRemembered(key, ApprovalState.ASK)
                assertEquals(ApprovalState.ASK, policy.state(key, "CHAT"))
                policy.setSessionAllow(key, "CHAT")
                policy.clearRemembered()
                assertEquals(ApprovalState.ASK, policy.state(key, "CHAT"))
            }
        }
    }
}
