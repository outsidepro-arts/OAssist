package ru.outsideproarts.oassist.vis

import kotlinx.coroutines.flow.MutableStateFlow
import ru.outsideproarts.oassist.core.model.TtsConfig
import ru.outsideproarts.oassist.core.speech.SpeechHandle

/** Handoff fixtures exercise real Android components without depending on the emulator's TTS engine. */
internal class QuietAssistSpeechHandle(delegate: SpeechHandle) : SpeechHandle by delegate {
    override fun speak(sentence: String) = Unit
    override suspend fun speakWhenReady(text: String) = false
    override suspend fun awaitIdle() = Unit
    override fun stop() = Unit
    override val speaking = MutableStateFlow(false)
    override fun applyTts(config: TtsConfig) = Unit
    override fun setNarrativeEnabled(enabled: Boolean) = Unit
}
