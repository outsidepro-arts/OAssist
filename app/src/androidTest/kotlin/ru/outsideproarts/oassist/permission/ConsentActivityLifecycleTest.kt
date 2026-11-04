package ru.outsideproarts.oassist.permission

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.ApprovalUiBridge
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.feature.assistant.ConsentActivity

@RunWith(AndroidJUnit4::class)
class ConsentActivityLifecycleTest {
    @Test
    fun recreationKeepsApprovalAndCompetingRequestFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = "lifecycle-first"
        val second = "lifecycle-second"
        val firstResolved = CountDownLatch(1)
        val secondResolved = CountDownLatch(1)
        var firstDecision: ApprovalDecision? = null
        var secondDecision: ApprovalDecision? = null
        ApprovalUiBridge.register(first) { firstDecision = it; firstResolved.countDown(); true }
        ApprovalUiBridge.register(second) { secondDecision = it; secondResolved.countDown(); true }
        fun intent(id: String) = ConsentActivity.approvalIntent(
            context, ApprovalRequest(id, "tool", "{}", "summary", "group"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            ActivityScenario.launch<ConsentActivity>(intent(first)).use { scenario ->
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(1L, firstResolved.count)
                context.startActivity(intent(first))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(1L, firstResolved.count)
                context.startActivity(intent(second))
                assertTrue(secondResolved.await(5, TimeUnit.SECONDS))
                assertEquals(ApprovalDecision.DENY, secondDecision)
                assertEquals(1L, firstResolved.count)
                scenario.onActivity { it.finish() }
                assertTrue(firstResolved.await(5, TimeUnit.SECONDS))
                assertEquals(ApprovalDecision.DENY, firstDecision)
            }
        } finally {
            ApprovalUiBridge.clear(first)
            ApprovalUiBridge.clear(second)
        }
    }
}
