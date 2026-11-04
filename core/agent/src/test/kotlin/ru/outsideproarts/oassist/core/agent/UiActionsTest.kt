package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.device.AccessibilityReassertCoordinator
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.device.PrivExecutor

class UiActionsTest {

    @Test
    fun globalAction_withoutAccess_reportsNotGranted() {
        var reconnects = 0
        val result = runBlocking {
            UiActions(
                serviceProvider = { null },
                accessGranted = { false },
                requestReconnect = { reconnects++ },
                connectTimeoutMs = 0,
                connectPollMs = 1,
            ).globalAction("back")
        }
        assertFalse(result.success)
        assertEquals(UiActions.SERVICE_DISABLED_MESSAGE, result.output)
        assertEquals(0, reconnects)
    }

    @Test
    fun globalAction_grantedButNotConnected_reportsNotConnected() {
        var reconnects = 0
        val result = runBlocking {
            UiActions(
                serviceProvider = { null },
                accessGranted = { true },
                requestReconnect = { reconnects++ },
                connectTimeoutMs = 20,
                connectPollMs = 5,
            ).globalAction("back")
        }
        assertFalse(result.success)
        assertEquals(UiActions.SERVICE_NOT_CONNECTED_MESSAGE, result.output)
        assertEquals(1, reconnects)
    }

    @Test
    fun globalAction_reconnectThrows_stillReportsNotConnected() {
        val result = runBlocking {
            UiActions(
                serviceProvider = { null },
                accessGranted = { true },
                requestReconnect = { error("settings write failed") },
                connectTimeoutMs = 0,
                connectPollMs = 1,
            ).globalAction("home")
        }
        assertFalse(result.success)
        assertEquals(UiActions.SERVICE_NOT_CONNECTED_MESSAGE, result.output)
    }

    @Test
    fun globalAction_restoreFailureIsNotHidden() {
        val executor = object : PrivExecutor by NoopExecutor() {
            override suspend fun reassertAccessibilityService(component: String, onCompletion: () -> Unit): Result<Unit> {
                onCompletion()
                return Result.failure(IllegalStateException("restore failed"))
            }
        }
        val failure = runCatching {
            runBlocking {
                UiActions(
                    serviceProvider = { null },
                    accessGranted = { true },
                    requestReconnect = { requestAccessibilityReassert { AccessibilityReassertCoordinator().run("component") { executor } } },
                    connectTimeoutMs = 0,
                    connectPollMs = 1,
                ).globalAction("home")
            }
        }.exceptionOrNull()
        assertTrue(failure is AccessibilityRestoreException)
    }

    @Test
    fun globalAction_withoutPrivilegesStillPollsForConnection() {
        var polls = 0
        val coordinator = AccessibilityReassertCoordinator()
        val result = runBlocking {
            UiActions(
                serviceProvider = { polls++; null },
                accessGranted = { true },
                requestReconnect = { requestAccessibilityReassert { coordinator.run("component") { NoopExecutor() } } },
                connectTimeoutMs = 30,
                connectPollMs = 5,
            ).globalAction("home")
        }
        assertFalse(result.success)
        assertEquals(UiActions.SERVICE_NOT_CONNECTED_MESSAGE, result.output)
        assertTrue(polls > 1)
    }
}
