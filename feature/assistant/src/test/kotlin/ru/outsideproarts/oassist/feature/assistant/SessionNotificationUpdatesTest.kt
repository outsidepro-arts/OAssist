package ru.outsideproarts.oassist.feature.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionNotificationUpdatesTest {
    @Test fun fastFinishSpeechAndFinallyPublishLatestStateAfterForeground() = runTest {
        var state = "Thinking"
        val sent = mutableListOf<Pair<Long, String>>()
        val updates = SessionNotificationUpdates(this, { testScheduler.currentTime }) {
            sent += testScheduler.currentTime to state
        }
        // Foreground publication itself is immediate, outside the update scheduler.
        sent += 0L to state
        updates.foregroundSent()
        updates.request() // Finish still has isStreaming=true.
        runCurrent()
        advanceTimeBy(10)
        updates.request() // Speech callback.
        state = "Finished answer"
        updates.request() // finally clears isStreaming.
        advanceTimeBy(489)
        runCurrent()
        assertEquals(listOf(0L to "Thinking"), sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L to "Thinking", 500L to "Finished answer"), sent)
        advanceTimeBy(2_000)
        assertEquals(2, sent.size)
    }

    @Test fun continuousUpdatesDoNotStarveAndBoundaryRequestIsNotLost() = runTest {
        var state = 0
        val sent = mutableListOf<Pair<Long, Int>>()
        val updates = SessionNotificationUpdates(this, { testScheduler.currentTime }) {
            sent += testScheduler.currentTime to state
        }
        updates.foregroundSent()
        repeat(10) {
            state++
            updates.request()
            advanceTimeBy(100)
            runCurrent()
        }
        assertEquals(listOf(500L to 5, 1_000L to 10), sent)
        state = 11
        updates.request()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1_500L to 11, sent.last())
    }

    @Test fun mandatoryForegroundTypeChangeMovesPendingDeadline() = runTest {
        val sent = mutableListOf<Long>()
        val updates = SessionNotificationUpdates(this, { testScheduler.currentTime }) {
            sent += testScheduler.currentTime
        }
        updates.foregroundSent()
        updates.request()
        advanceTimeBy(400)
        updates.foregroundSent() // Mandatory platform call was not delayed.
        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList<Long>(), sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(900L), sent)
    }

    @Test fun destroyCancelsPendingAndRejectsLateCallbacksWhileNewOwnerCanPublish() = runTest {
        val sent = mutableListOf<String>()
        val old = SessionNotificationUpdates(this, { testScheduler.currentTime }) { sent += "old" }
        old.foregroundSent()
        old.request()
        runCurrent()
        old.close()
        old.request()
        val replacement = SessionNotificationUpdates(this, { testScheduler.currentTime }) { sent += "new" }
        replacement.foregroundSent()
        replacement.request()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("new"), sent)
    }

    @Test fun cancellingServiceScopeCannotPublishPendingUpdate() = runTest {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        var sends = 0
        val updates = SessionNotificationUpdates(owner, { testScheduler.currentTime }) { sends++ }
        updates.foregroundSent()
        updates.request()
        runCurrent()
        owner.cancel()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, sends)
    }
}
