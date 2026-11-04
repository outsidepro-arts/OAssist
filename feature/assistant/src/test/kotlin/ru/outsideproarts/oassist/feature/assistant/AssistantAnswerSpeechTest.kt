package ru.outsideproarts.oassist.feature.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.outsideproarts.oassist.core.agent.AssistVoiceEffect
import ru.outsideproarts.oassist.core.agent.AssistVoiceFlow
import ru.outsideproarts.oassist.core.agent.AssistVoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAnswerSpeechTest {
    @Test
    fun regularTurnWaitsForAcceptedSpeechBeforeContinuing() = runBlocking {
        val receipt = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val flow = AssistVoiceFlow().apply { onUserSubmit("Вопрос") }
        val speaker = AssistantAnswerSpeech { events += "submit:$it" }
        speaker.onText("Первое.", false)
        speaker.onFinish("Первое.", false)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            finishSpokenTurn(awaitIdle = { receipt.await() }, continueTurn = {
                if (AssistVoiceEffect.StartStt in flow.onTurnFinished(it)) events += "startStt"
            })
        }
        assertEquals(listOf("submit:Первое."), events)
        receipt.complete(Unit)
        job.join()
        assertEquals(listOf("submit:Первое.", "startStt"), events)
    }

    @Test
    fun regularTurnTimeoutDoesNotStartSttAndExternalCancellationDoesNotContinue() = runBlocking {
        val receipt = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        finishSpokenTurn({ receipt.await() }, { if (it) events += "startStt" else events += "idle" }, timeoutMs = 0)
        assertEquals(listOf("idle"), events)
        assertFalse(receipt.isCompleted)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            finishSpokenTurn({ receipt.await() }, { events += "unexpected" })
        }
        job.cancelAndJoin()
        assertFalse(receipt.isCompleted)
        assertEquals(listOf("idle"), events)
    }

    @Test
    fun timedOutTurnReturnsToIdleAndAllowsManualStart() = runBlocking {
        val flow = AssistVoiceFlow()
        flow.onUserSubmit("Вопрос")
        val receipt = CompletableDeferred<Unit>()
        var effects = emptyList<AssistVoiceEffect>()
        finishSpokenTurn({ receipt.await() }, { effects = flow.onTurnFinished(it) }, timeoutMs = 0)
        assertTrue(effects.isEmpty())
        assertEquals(AssistVoiceState.Idle, flow.state.value)
        assertFalse(receipt.isCompleted)
        assertEquals(listOf(AssistVoiceEffect.StartStt), flow.onPlayPause())
    }

    @Test
    fun regularTurnDoesNotContinueWhenDrainFails() = runBlocking {
        val failure = IllegalStateException("drain failed")
        var continued = false
        val caught = runCatching {
            finishSpokenTurn({ throw failure }, { continued = true })
        }.exceptionOrNull()
        assertTrue(caught is IllegalStateException)
        assertEquals(failure.message, caught?.message)
        assertFalse(continued)
    }

    @Test
    fun terminalFarewellIsSubmittedOnceAfterFinishAndThenDrained() = runBlocking {
        val events = mutableListOf<String>()
        val speaker = AssistantAnswerSpeech { events += "stream:$it" }
        speaker.onText("До встречи!", true)
        speaker.onFinish("До встречи!", true)
        assertTrue(events.isEmpty())

        val result = speaker.finishTerminal(
            autoSpeak = true,
            speakWhenReady = { events += "final:$it"; true },
            awaitIdle = { events += "drain" },
            stop = { events += "stop" },
        )
        assertTrue(result)
        assertEquals(listOf("final:До встречи!", "drain"), events)

        speaker.finishTerminal(true, { events += "duplicate:$it"; true }, {}, {})
        assertEquals(listOf("final:До встречи!", "drain"), events)
    }

    @Test
    fun terminalFailureStillDrainsAndDisabledSpeechSkipsSubmission() = runBlocking {
        val events = mutableListOf<String>()
        val speaker = AssistantAnswerSpeech { events += "stream:$it" }
        speaker.onText("До встречи!", true)
        speaker.onFinish("До встречи!", true)
        assertFalse(speaker.finishTerminal(true, { events += "failed"; false }, { events += "drain" }, { events += "stop" }))
        assertEquals(listOf("failed", "drain"), events)

        speaker.onText("Пока!", true)
        speaker.onFinish("Пока!", true)
        assertTrue(speaker.finishTerminal(false, { events += "unexpected"; false }, { events += "drain" }, { events += "stop" }))
        assertEquals(listOf("failed", "drain", "drain"), events)
    }

    @Test
    fun terminalFarewellSubmitsEachSentenceInOrder() = runBlocking {
        val events = mutableListOf<String>()
        val speaker = AssistantAnswerSpeech { events += "stream:$it" }
        speaker.onText("Пожалуйста! До встречи.", true)
        speaker.onFinish("Пожалуйста! До встречи.", true)
        val result = speaker.finishTerminal(true, { events += it; true }, { events += "drain" }, { events += "stop" })
        assertTrue(result)
        assertEquals(listOf("Пожалуйста!", "До встречи.", "drain"), events)
    }

    @Test
    fun hungSubmissionOrDrainTimesOutAndStopsWithoutRepeatingSpeech() = runBlocking {
        for (hangDuringSubmission in listOf(true, false)) {
            val events = mutableListOf<String>()
            val speaker = AssistantAnswerSpeech(speak = { events += "stream:$it" }, terminalTimeoutMs = 100)
            speaker.onText("До встречи!", true)
            speaker.onFinish("До встречи!", true)
            val result = speaker.finishTerminal(
                true,
                { events += "submit:$it"; if (hangDuringSubmission) awaitCancellation() else true },
                { events += "drain"; if (!hangDuringSubmission) awaitCancellation() },
                { events += "stop" },
            )
            assertFalse(result)
            assertEquals(1, events.count { it.startsWith("submit:") })
            assertEquals(1, events.count { it == "stop" })
        }
    }

    @Test
    fun externalCancellationIsNotConvertedIntoTimeoutOrStop() = runBlocking {
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val speaker = AssistantAnswerSpeech(speak = {}, terminalTimeoutMs = 30_000)
        speaker.onText("До встречи!", true)
        speaker.onFinish("До встречи!", true)
        val job = launch {
            speaker.finishTerminal(true, { started.complete(Unit); awaitCancellation() }, {}, { events += "stop" })
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun regularAnswerStillStreamsAndFinishesItsTail() {
        val spoken = mutableListOf<String>()
        val speaker = AssistantAnswerSpeech { spoken += it }
        speaker.onText("Первое. Второе", false)
        speaker.onFinish("Первое. Второе", false)
        assertEquals(listOf("Первое.", "Второе"), spoken)
    }
}
