package ru.outsideproarts.oassist.feature.assistant

import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.StreamSpeaker

internal class AssistantAnswerSpeech(
    private val terminalTimeoutMs: Long = TERMINAL_SPEECH_TIMEOUT_MS,
    speak: (String) -> Unit,
) {
    private val streaming = StreamSpeaker(speak = speak, speakToolCalls = true)
    private var terminalFarewell: String? = null

    fun onText(full: String, isTerminalFarewell: Boolean) {
        if (isTerminalFarewell) terminalFarewell = full
        else streaming.onText(full, false)
    }

    fun onFinish(full: String, closeSession: Boolean) {
        if (!closeSession || terminalFarewell == null) streaming.onFinish(full)
    }

    suspend fun finishTerminal(
        autoSpeak: Boolean,
        speakWhenReady: suspend (String) -> Boolean,
        awaitIdle: suspend () -> Unit,
        stop: () -> Unit,
    ): Boolean {
        val farewell = terminalFarewell
        terminalFarewell = null
        val settled = withTimeoutOrNull(terminalTimeoutMs) {
            var accepted = true
            if (autoSpeak && farewell != null) {
                val sentences = SentenceSplitter.splitSentences(SentenceSplitter.stripServiceLines(farewell))
                if (sentences.isEmpty()) accepted = false
                for (sentence in sentences) {
                    if (!speakWhenReady(sentence)) {
                        accepted = false
                        break
                    }
                }
            }
            awaitIdle()
            accepted
        }
        if (settled == null) {
            stop()
            return false
        }
        return settled
    }

    private companion object {
        const val TERMINAL_SPEECH_TIMEOUT_MS = 30_000L
    }
}
