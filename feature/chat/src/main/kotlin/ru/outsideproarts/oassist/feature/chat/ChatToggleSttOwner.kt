package ru.outsideproarts.oassist.feature.chat

import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.VoiceHandle

internal class ChatToggleSttOwner(private val voice: VoiceHandle?) {
    private val listeners = mutableSetOf<SttListener>()
    private var closed = false
    private var currentDestination = true
    private var pendingResume: (() -> Unit)? = null
    var active = true
        private set

    fun resume() {
        if (closed) return
        currentDestination = true
        active = true
        val pending = pendingResume
        pendingResume = null
        pending?.invoke()
    }

    fun whenActive(action: () -> Unit) {
        if (closed || !currentDestination) return
        if (active) action() else pendingResume = action
    }

    fun leaveDestination() {
        pause()
        currentDestination = false
        pendingResume = null
    }

    fun start(language: String, delegate: SttListener, component: String?, bluetooth: Boolean): CaptureError? {
        val handle = voice ?: return CaptureError.NO_RECOGNIZER
        if (!active) return null
        val listener = object : SttListener {
            override fun onPartial(text: String) {
                if (active && this in listeners) delegate.onPartial(text)
            }

            override fun onFinal(text: String) {
                if (active && listeners.remove(this)) delegate.onFinal(text)
            }

            override fun onSttError(error: SttError) {
                if (active && listeners.remove(this)) delegate.onSttError(error)
            }

            override fun onSttCancelled() {
                if (active && listeners.remove(this)) delegate.onSttCancelled()
            }
        }
        listeners.add(listener)
        val error = handle.startStt(language, listener, component, bluetooth)
        if (error != null) listeners.remove(listener)
        return error
    }

    fun pause() {
        if (!active) return
        active = false
        pendingResume = null
        val owned = listeners.toList()
        listeners.clear()
        owned.forEach { voice?.cancelStt(it) }
    }

    fun dispose() {
        pause()
        closed = true
        pendingResume = null
    }
}
