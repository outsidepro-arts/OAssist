package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotifyActionsTest {

    @Test
    fun list_withoutAccess_reportsNotGranted() {
        var rebinds = 0
        val result = runBlocking {
            NotifyActions(
                serviceProvider = { null },
                accessGranted = { false },
                requestRebind = { rebinds++ },
                connectTimeoutMs = 0,
                connectPollMs = 1,
            ).list()
        }
        assertFalse(result.success)
        assertEquals(NotifyActions.SERVICE_DISABLED_MESSAGE, result.output)
        assertEquals(0, rebinds)
    }

    @Test
    fun list_grantedButNotConnected_reportsNotConnected() {
        var rebinds = 0
        val result = runBlocking {
            NotifyActions(
                serviceProvider = { null },
                accessGranted = { true },
                requestRebind = { rebinds++ },
                connectTimeoutMs = 20,
                connectPollMs = 5,
            ).list()
        }
        assertFalse(result.success)
        assertEquals(NotifyActions.SERVICE_NOT_CONNECTED_MESSAGE, result.output)
        assertEquals(1, rebinds)
    }

    @Test
    fun list_rebindThrows_stillReportsNotConnected() {
        val result = runBlocking {
            NotifyActions(
                serviceProvider = { null },
                accessGranted = { true },
                requestRebind = { error("binder died") },
                connectTimeoutMs = 0,
                connectPollMs = 1,
            ).list()
        }
        assertFalse(result.success)
        assertEquals(NotifyActions.SERVICE_NOT_CONNECTED_MESSAGE, result.output)
    }
}
