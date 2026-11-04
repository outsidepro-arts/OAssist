package ru.outsideproarts.oassist.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome

class PermissionResultOutcomeTest {
    @Test fun interruptedOrIncompleteResultsAreUnavailable() {
        val permissions = listOf("camera", "microphone")
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, permissionResultOutcome(permissions, emptyMap()) { false })
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, permissionResultOutcome(permissions, mapOf("camera" to true)) { false })
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, permissionResultOutcome(emptyList(), emptyMap()) { false })
    }

    @Test fun completeResultsReflectGrantAndDenial() {
        val permissions = listOf("camera", "microphone")
        assertEquals(PermissionRequestOutcome.GRANTED, permissionResultOutcome(permissions, permissions.associateWith { true }) { false })
        val denied = mapOf("camera" to true, "microphone" to false)
        assertEquals(PermissionRequestOutcome.DENIED, permissionResultOutcome(permissions, denied) { it == "microphone" })
        assertEquals(PermissionRequestOutcome.DENIED_FOREVER, permissionResultOutcome(permissions, denied) { false })
    }
}
