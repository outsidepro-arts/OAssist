package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistVoiceFlowTest {

    private val flow = AssistVoiceFlow()

    @Test
    fun activate_startsListeningWithLoopOn() {
        val effects = flow.onActivate()

        assertEquals(listOf(AssistVoiceEffect.StartStt), effects)
        assertEquals(AssistVoiceState.Listening, flow.state.value)
        assertTrue(flow.voiceLoopOn)
    }

    @Test
    fun reactivate_isFullResetOnWithBargeIn() {
        flow.onActivate()
        flow.onSttFinal("вопрос")
        assertEquals(AssistVoiceState.Busy, flow.state.value)

        val effects = flow.onReactivate()

        assertEquals(
            listOf(AssistVoiceEffect.CancelTurn, AssistVoiceEffect.CancelStt, AssistVoiceEffect.StartStt),
            effects,
        )
        assertEquals(AssistVoiceState.Listening, flow.state.value)
        assertTrue(flow.voiceLoopOn)
    }

    @Test
    fun sttFinal_nonBlank_sendsText() {
        flow.onActivate()

        val effects = flow.onSttFinal("  включи фонарик  ")

        assertEquals(listOf(AssistVoiceEffect.SendText("включи фонарик")), effects)
        assertEquals(AssistVoiceState.Busy, flow.state.value)
    }

    @Test
    fun sttFinal_blank_doesNothing() {
        flow.onActivate()

        val effects = flow.onSttFinal("   ")

        assertTrue(effects.isEmpty())
        assertEquals(AssistVoiceState.Idle, flow.state.value)
    }

    @Test
    fun userSubmit_whileListening_cancelsSttAndSends() {
        flow.onActivate()

        val effects = flow.onUserSubmit("  напиши письмо  ")

        assertEquals(
            listOf(AssistVoiceEffect.CancelStt, AssistVoiceEffect.SendText("напиши письмо")),
            effects,
        )
        assertEquals(AssistVoiceState.Busy, flow.state.value)
        assertTrue(flow.voiceLoopOn)
    }

    @Test
    fun userSubmit_whileIdle_sends() {
        flow.onActivate()
        flow.onSttFinal("   ")
        assertEquals(AssistVoiceState.Idle, flow.state.value)

        val effects = flow.onUserSubmit("вопрос")

        assertEquals(listOf(AssistVoiceEffect.SendText("вопрос")), effects)
        assertEquals(AssistVoiceState.Busy, flow.state.value)
    }

    @Test
    fun userSubmit_whileBusy_ignored() {
        flow.onActivate()
        flow.onSttFinal("первый")

        assertTrue(flow.onUserSubmit("второй").isEmpty())
        assertEquals(AssistVoiceState.Busy, flow.state.value)
    }

    @Test
    fun playPause_whileListening_sendsPartialAndSuppressesAutoDialog() {
        flow.onActivate()
        flow.onSttPartial("напиши письмо")

        val effects = flow.onPlayPause()

        assertEquals(
            listOf(AssistVoiceEffect.CancelStt, AssistVoiceEffect.SendText("напиши письмо")),
            effects,
        )
        assertEquals(AssistVoiceState.Busy, flow.state.value)
        assertFalse(flow.voiceLoopOn)

        assertEquals(emptyList<AssistVoiceEffect>(), flow.onTurnFinished(continueDialog = true))
        assertEquals(AssistVoiceState.Idle, flow.state.value)
    }

    @Test
    fun playPause_whileListeningWithoutText_stopsSilently() {
        flow.onActivate()

        val effects = flow.onPlayPause()

        assertEquals(listOf(AssistVoiceEffect.CancelStt), effects)
        assertEquals(AssistVoiceState.Idle, flow.state.value)
        assertFalse(flow.voiceLoopOn)
    }

    @Test
    fun playPause_whileIdle_turnsLoopOnAndListens() {
        flow.onActivate()
        flow.onPlayPause()
        assertFalse(flow.voiceLoopOn)

        val effects = flow.onPlayPause()

        assertEquals(listOf(AssistVoiceEffect.StartStt), effects)
        assertEquals(AssistVoiceState.Listening, flow.state.value)
        assertTrue(flow.voiceLoopOn)
    }

    @Test
    fun playPause_whileBusy_isBargeInAndListen() {
        flow.onActivate()
        flow.onSttFinal("вопрос")

        val effects = flow.onPlayPause()

        assertEquals(listOf(AssistVoiceEffect.CancelTurn, AssistVoiceEffect.StartStt), effects)
        assertEquals(AssistVoiceState.Listening, flow.state.value)
        assertTrue(flow.voiceLoopOn)
    }

    @Test
    fun turnFinished_continueDialogRestartsListening() {
        flow.onActivate()
        flow.onSttFinal("вопрос")

        val effects = flow.onTurnFinished(continueDialog = true)

        assertEquals(listOf(AssistVoiceEffect.StartStt), effects)
        assertEquals(AssistVoiceState.Listening, flow.state.value)
    }

    @Test
    fun turnFinished_withoutContinueDialogGoesIdle() {
        flow.onActivate()
        flow.onSttFinal("вопрос")

        val effects = flow.onTurnFinished(continueDialog = false)

        assertTrue(effects.isEmpty())
        assertEquals(AssistVoiceState.Idle, flow.state.value)
    }

    @Test
    fun sttStartFailed_fallsBackToIdle() {
        flow.onActivate()

        flow.onSttStartFailed()

        assertEquals(AssistVoiceState.Idle, flow.state.value)
    }

    @Test
    fun close_endsSession() {
        flow.onActivate()

        val effects = flow.onClose()

        assertEquals(listOf(AssistVoiceEffect.CancelStt, AssistVoiceEffect.CancelTurn), effects)
        assertEquals(AssistVoiceState.Ended, flow.state.value)
        assertTrue(flow.onPlayPause().isEmpty())
    }
}
