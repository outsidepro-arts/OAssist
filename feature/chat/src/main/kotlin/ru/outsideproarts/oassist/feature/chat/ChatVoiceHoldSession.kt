package ru.outsideproarts.oassist.feature.chat

import ru.outsideproarts.oassist.core.speech.CaptureMode

internal class ChatVoiceHoldSession(val mode: CaptureMode) {
    var captureActive: Boolean = true
        private set
    private var released = false
    private var pendingFinal: String? = null

    fun onFinal(text: String): String? {
        if (!captureActive) return null
        captureActive = false
        if (released) return text
        pendingFinal = text
        return null
    }

    fun onError() {
        captureActive = false
        pendingFinal = null
    }

    fun release(): String? {
        released = true
        return pendingFinal.also { pendingFinal = null }
    }

    fun cancel(): Boolean {
        val wasActive = captureActive
        captureActive = false
        pendingFinal = null
        return wasActive
    }
}
