package ru.outsideproarts.oassist

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserHandle
import android.service.notification.Condition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.DeviceActions

@RunWith(AndroidJUnit4::class)
class DndContractInstrumentedTest {
    @Test
    fun otherActiveRuleKeepsGlobalDndAfterAppRequestsOff() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)!!
        val initiallyGranted = manager.isNotificationPolicyAccessGranted
        if (!initiallyGranted) {
            assertEquals("DND fixture requires the primary Android user", UserHandle.getUserHandleForUid(0), Process.myUserHandle())
        }
        try {
            if (!initiallyGranted) {
                setPolicyAccess(manager, context.packageName, granted = true)
            }
            assertTrue("DND policy access was not granted", manager.isNotificationPolicyAccessGranted)
            assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, manager.currentInterruptionFilter)
            val initialRules = manager.automaticZenRules.keys.toSet()
            // An existing implicit app rule could be changed by setDnd(false).
            assertTrue("DND fixture requires no existing app rules", initialRules.isEmpty())
            val conditionId = Uri.parse("condition://${context.packageName}/ad004-verification")
            val rule = AutomaticZenRule.Builder("AD-004 verification", conditionId)
                .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
                .setEnabled(true)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                .build()
            val ruleId = requireNotNull(manager.addAutomaticZenRule(rule))
            try {
                manager.setAutomaticZenRuleState(ruleId, Condition(conditionId, "AD-004", Condition.STATE_TRUE))
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE &&
                    android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                }
                assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, manager.currentInterruptionFilter)
                val result = DeviceActions(context).setDnd(false)
                val observedAfter = manager.currentInterruptionFilter
                println("AD004 result=${result.output} currentInterruptionFilter=$observedAfter")
                assertTrue(result.success)
                val output = JSONObject(result.output)
                assertTrue(output.getBoolean("requestAccepted"))
                assertEquals("all", output.getString("requestedFilter"))
                assertEquals("app_rule", output.getString("scope"))
                assertFalse(output.getBoolean("globalOutcomeConfirmed"))
                assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, observedAfter)
                assertEquals(observedAfter, output.getInt("observedGlobalFilterValue"))
                assertEquals("none", output.getString("observedGlobalFilter"))
            } finally {
                assertTrue(manager.removeAutomaticZenRule(ruleId))
                assertFalse(manager.automaticZenRules.containsKey(ruleId))
                assertTrue(manager.automaticZenRules.keys.containsAll(initialRules))
            }
        } finally {
            if (!initiallyGranted) {
                setPolicyAccess(manager, context.packageName, granted = false)
            }
        }
    }

    private fun setPolicyAccess(manager: NotificationManager, packageName: String, granted: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val action = if (granted) "allow_dnd" else "disallow_dnd"
        val command = "cmd notification $action $packageName 0"
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (manager.isNotificationPolicyAccessGranted != granted &&
            android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("DND access not changed by $command; output: $output", granted, manager.isNotificationPolicyAccessGranted)
    }
}
