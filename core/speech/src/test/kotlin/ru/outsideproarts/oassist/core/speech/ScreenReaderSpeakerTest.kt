package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReaderSpeakerTest {

    @Test
    fun announcesInOrder_blankIgnored() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("Первое.")
        speaker.announce("   ")
        speaker.announce("Второе.")
        runCurrent()
        assertEquals(listOf("Первое.", "Второе."), said)
        assertFalse(speaker.speaking.value)
    }

    @Test
    fun identicalSentences_notSwallowed() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("Готово.")
        speaker.announce("Готово.")
        runCurrent()
        assertEquals(listOf("Готово.", "Готово."), said)
    }

    @Test
    fun stop_dropsPending_keepsSpoken() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var parked = false
        val speaker = ScreenReaderSpeaker(
            scope = this,
            output = said::add,
            delayer = {
                if (!parked) {
                    parked = true
                    gate.await()
                }
            },
        )
        speaker.announce("Первое.")
        speaker.announce("Второе.")
        runCurrent()
        assertEquals(listOf("Первое."), said)
        assertTrue(speaker.speaking.value)
        speaker.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Первое."), said)
        assertFalse(speaker.speaking.value)
    }

    @Test
    fun assertive_clearsQueue_speaksImmediately() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var parked = false
        val speaker = ScreenReaderSpeaker(
            scope = this,
            output = said::add,
            delayer = {
                if (!parked) {
                    parked = true
                    gate.await()
                }
            },
        )
        speaker.announce("Первое.")
        speaker.announce("Второе.")
        runCurrent()
        speaker.announceAssertive("Ошибка!")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Первое.", "Ошибка!"), said)
        assertFalse(speaker.speaking.value)
    }

    @Test
    fun awaitIdle_waitsForAnnouncementBeforeEnqueueRuns() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("До встречи!")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        assertFalse(waiting.isCompleted)
        runCurrent()
        assertEquals(listOf("До встречи!"), said)
        assertTrue(waiting.isCompleted)
        waiting.await()
    }

    @Test
    fun awaitIdle_waitsForAllPreviouslySubmittedAnnouncements() = runTest {
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {
            if (first) {
                first = false
                gate.await()
            }
        })
        speaker.announce("Первое.")
        speaker.announce("Последнее.")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        runCurrent()
        assertEquals(listOf("Первое."), said)
        assertFalse(waiting.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Первое.", "Последнее."), said)
        assertTrue(waiting.isCompleted)
        waiting.await()
    }

    @Test
    fun stopBeforeEnqueue_dropsOldSubmissionButKeepsNewOne() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("Старое.")
        val oldWaiter = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        speaker.stop()
        speaker.announce("Новое.")
        val newWaiter = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        runCurrent()
        assertEquals(listOf("Новое."), said)
        assertTrue(oldWaiter.isCompleted)
        assertTrue(newWaiter.isCompleted)
        oldWaiter.await()
        newWaiter.await()
    }

    @Test
    fun shutdownBeforeEnqueue_resolvesWaitAndDoesNotDispatch() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("Не прозвучит.")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        speaker.shutdown()
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertTrue(said.isEmpty())
        waiting.await()
    }

    @Test
    fun cancelledWaiter_doesNotCancelTheAnnouncementReceipt() = runTest {
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = this, output = said::add, delayer = {})
        speaker.announce("До свидания!")
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        cancelled.cancel()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        assertFalse(waiting.isCompleted)
        runCurrent()
        assertEquals(listOf("До свидания!"), said)
        assertTrue(waiting.isCompleted)
        waiting.await()
    }

    @Test
    fun outputFailure_resolvesReceiptAndAllowsFollowingAnnouncement() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> failures.add(error) },
        )
        val said = mutableListOf<String>()
        val speaker = ScreenReaderSpeaker(scope = scope, output = {
            if (it == "Ошибка.") throw IllegalStateException("output failed")
            said.add(it)
        }, delayer = {})
        speaker.announce("Ошибка.")
        speaker.announce("После ошибки.")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { speaker.awaitIdle() }
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(listOf("После ошибки."), said)
        assertTrue(waiting.isCompleted)
        waiting.await()
        speaker.shutdown()
        scope.cancel()
    }

    @Test
    fun staleEnqueueCompletionDoesNotClearSpeakingDuringNewWorkerDelay() = runTest {
        val dispatched = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.add(block)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val said = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val speaker = ScreenReaderSpeaker(scope = scope, output = said::add, delayer = { gate.await() })

        speaker.announce("Старое.")
        assertEquals(1, dispatched.size)
        speaker.stop()
        dispatched.removeAt(1).run()
        speaker.announce("Новое.")
        dispatched.removeAt(1).run()
        dispatched.removeAt(1).run()
        assertEquals(listOf("Новое."), said)
        assertTrue(speaker.speaking.value)

        dispatched.removeAt(0).run()
        assertTrue(speaker.speaking.value)
        gate.complete(Unit)
        while (dispatched.isNotEmpty()) dispatched.removeAt(0).run()
        assertFalse(speaker.speaking.value)
        speaker.shutdown()
        scope.cancel()
    }
}
