package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCompletionTrackerTest {
    @Test
    fun snapshotWaitsForEarlierCompletionsAndNotLaterSubmissions() = runTest {
        val tracker = SpeechCompletionTracker()
        val first = tracker.register()
        val second = tracker.register()
        val snapshot = tracker.snapshot()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { snapshot.awaitAll() }
        assertFalse(waiting.isCompleted)
        val later = tracker.register()
        tracker.complete(first)
        assertFalse(waiting.isCompleted)
        tracker.complete(second)
        waiting.await()
        assertFalse(later.isCompleted)
        tracker.completeAll()
        assertTrue(later.isCompleted)
    }

    @Test
    fun cancellationOfWaiterDoesNotDiscardPendingWork() = runTest {
        val tracker = SpeechCompletionTracker()
        val pending = tracker.register()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { tracker.snapshot().awaitAll() }
        cancelled.cancel()
        assertFalse(pending.isCompleted)
        tracker.complete(pending)
        tracker.snapshot().awaitAll()
        assertTrue(pending.isCompleted)
    }
}
