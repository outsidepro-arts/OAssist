package ru.outsideproarts.oassist.core.net

import kotlinx.coroutines.flow.StateFlow

/**
 * Мост состояния сети для `feature:*` без зависимости от `app`
 * (тот же паттерн, что `SpeechHandle`/`VoiceHandle`).
 */
interface NetworkHandle {
    val isOnline: StateFlow<Boolean>

    companion object {
        private var instance: NetworkHandle? = null

        fun register(handle: NetworkHandle) {
            instance = handle
        }

        fun get(): NetworkHandle? = instance
    }
}
