package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
