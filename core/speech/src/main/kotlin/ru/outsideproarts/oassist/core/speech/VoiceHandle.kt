package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Захват микрофона для `feature:*` без зависимости от `app`.
 * Регистрируется в `AppGraph` поверх [VoiceListener],
 * зеркалит паттерн [SpeechHandle].
 */
interface VoiceHandle {
    fun captureState(): StateFlow<CaptureState>
    fun isSttAvailable(recognizerComponent: String? = null): Boolean

    fun startStt(language: String, listener: SttListener, recognizerComponent: String? = null): CaptureError?
    fun stopStt()
    fun cancelStt()

    fun startRaw(maxSec: Int = VoiceListener.MAX_RAW_SEC): CaptureError?
    fun stopRaw(): WavAudio?
    fun cancelRaw()

    companion object {
        @Volatile
        private var instance: VoiceHandle? = null

        fun register(handle: VoiceHandle) {
            instance = handle
        }

        /** Null — захват недоступен (UI показывает подсказку, не падает). */
        fun get(): VoiceHandle? = instance
    }
}
