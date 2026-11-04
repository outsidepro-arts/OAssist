package ru.outsideproarts.oassist

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)!!
        assertTrue(manager.isNotificationPolicyAccessGranted)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, manager.currentInterruptionFilter)
        val initialRules = manager.automaticZenRules.keys.toSet()
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
    }
}
