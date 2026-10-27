package ru.outsideproarts.oassist.feature.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PendingSttStartTest {
    @Test
    fun lateOldPermissionDoesNotStopSpeechStartSttOrClearNewOwner() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pending = PendingSttStart(scope)
            val effects = mutableListOf<String>()
            var oldContinuation: Continuation<Unit>? = null
            var newContinuation: Continuation<Unit>? = null
            lateinit var oldIsCurrent: () -> Boolean
            lateinit var newIsCurrent: () -> Boolean
            var listening = true

            pending.start { isCurrent ->
                oldIsCurrent = isCurrent
                suspendCoroutine<Unit> { oldContinuation = it }
                if (isCurrent() && listening) {
                    effects += listOf("old hint", "old speech stop", "old STT start", "old error", "old sound")
                }
            }
            assertNotNull(oldContinuation)
            listening = false
            pending.cancel()
            listening = true
            pending.start { isCurrent ->
                newIsCurrent = isCurrent
                effects += "new STT start"
                suspendCoroutine<Unit> { newContinuation = it }
            }

            assertNotNull(newContinuation)
            assertFalse(oldIsCurrent())
            assertTrue(newIsCurrent())
            oldContinuation!!.resume(Unit)
            assertEquals(listOf("new STT start"), effects)
            assertTrue("Old completion must not clear the newer owner", newIsCurrent())

            pending.cancel()
            assertFalse(newIsCurrent())
            newContinuation!!.resume(Unit)
            assertEquals(listOf("new STT start"), effects)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationRemovesPendingPermissionAndLateGrantCannotStartOldCapture() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var unrelatedRequest: String? = null
        try {
            val pending = PendingSttStart(scope)
            val captures = mutableListOf<String>()
            var oldRequest: String? = null
            var newRequest: String? = null
            pending.start { isCurrent ->
                awaitBluetoothPermission { requestId, _ -> oldRequest = requestId; true }
                if (isCurrent()) captures += "old"
            }
            val oldId = requireNotNull(oldRequest)
            PermissionRequestBridge.markStarted(oldId)
            val unrelatedId = PermissionRequestBridge.register(listOf("another.permission"))
            unrelatedRequest = unrelatedId
            val unrelatedWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                PermissionRequestBridge.await(unrelatedId, startTimeoutMs = 0L, userTimeoutMs = 60_000L)
            }
            assertEquals(unrelatedId, PermissionRequestBridge.blocked.value?.requestId)
            pending.cancel()
            assertEquals(PermissionRequestOutcome.UNAVAILABLE, withTimeout(5_000L) { PermissionRequestBridge.await(oldId) })
            assertEquals("Cancelling the old request must not clear another blocked request", unrelatedId, PermissionRequestBridge.blocked.value?.requestId)
            PermissionRequestBridge.complete(unrelatedId, PermissionRequestOutcome.GRANTED)
            assertEquals(PermissionRequestOutcome.GRANTED, withTimeout(5_000L) { unrelatedWaiter.await() })

            pending.start { isCurrent ->
                awaitBluetoothPermission { requestId, _ -> newRequest = requestId; true }
                if (isCurrent()) captures += "new"
            }
            val newId = requireNotNull(newRequest)
            assertNotEquals(oldId, newId)
            PermissionRequestBridge.markStarted(newId)
            PermissionRequestBridge.complete(oldId, PermissionRequestOutcome.GRANTED)
            assertTrue(captures.isEmpty())
            PermissionRequestBridge.complete(newId, PermissionRequestOutcome.GRANTED)
            assertEquals(listOf("new"), captures)
            assertEquals(PermissionRequestOutcome.UNAVAILABLE, withTimeout(5_000L) { PermissionRequestBridge.await(newId) })
        } finally {
            unrelatedRequest?.let(PermissionRequestBridge::cancel)
            scope.cancel()
        }
    }

    @Test
    fun immediateCompletionReleasesOwnerAndNewStartCancelsPreviousOne() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pending = PendingSttStart(scope)
            lateinit var completedOwner: () -> Boolean
            var currentDuringImmediate = false
            pending.start { isCurrent ->
                completedOwner = isCurrent
                currentDuringImmediate = isCurrent()
            }
            assertTrue(currentDuringImmediate)
            assertFalse(completedOwner())

            var delayed: Continuation<Unit>? = null
            lateinit var replacedOwner: () -> Boolean
            pending.start { isCurrent ->
                replacedOwner = isCurrent
                suspendCoroutine<Unit> { delayed = it }
            }
            assertTrue(replacedOwner())
            var replacementWasCurrent = false
            pending.start { isCurrent -> replacementWasCurrent = isCurrent() }
            assertTrue(replacementWasCurrent)
            assertFalse(replacedOwner())
            requireNotNull(delayed).resume(Unit)
            pending.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedPermissionLaunchReleasesRegistration() = runBlocking {
        var requestId: String? = null
        val outcome = awaitBluetoothPermission { id, _ -> requestId = id; false }
        assertEquals(null, outcome)
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, withTimeout(5_000L) { PermissionRequestBridge.await(requireNotNull(requestId)) })
    }
}
