package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyActionsTest {

    @Test
    fun reply_overLimitIsRejectedBeforeAnyServiceAccess() = runBlocking {
        var serviceReads = 0
        val actions = NotifyActions(serviceProvider = { serviceReads++; null })
        for (text in listOf("x".repeat(501), "😀".repeat(501), "x".repeat(499) + "😀x")) {
            val result = actions.reply("key", text)
            assertFalse(result.success)
            assertTrue(result.output.contains("500"))
        }
        assertEquals(0, serviceReads)
    }

    @Test
    fun reply_blankIsRejectedBeforeAnyServiceAccess() = runBlocking {
        var serviceReads = 0
        val actions = NotifyActions(serviceProvider = { serviceReads++; null })
        for (text in listOf("", " \n\t")) {
            assertEquals("Пустой текст ответа", actions.reply("key", text).output)
        }
        assertEquals(0, serviceReads)
    }

    @Test
    fun reply_limitCountsUnicodeCodePointsAndAccepts500() = runBlocking {
        var serviceReads = 0
        val actions = NotifyActions(serviceProvider = { serviceReads++; null })
        for (text in listOf("x".repeat(500), "😀".repeat(500), "x".repeat(499) + "😀")) {
            assertEquals(NotifyActions.SERVICE_DISABLED_MESSAGE, actions.reply("key", text).output)
        }
        assertEquals(3, serviceReads)
    }

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
