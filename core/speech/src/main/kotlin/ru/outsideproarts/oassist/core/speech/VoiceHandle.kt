package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Захват микрофона для `feature:*` без зависимости от `app`.
 * Регистрируется в `AppGraph` поверх [VoiceListener],
 * зеркалит паттерн [SpeechHandle].
 */
interface VoiceHandle {
    fun captureState(): StateFlow<CaptureState>
    fun routeState(): StateFlow<VoiceInputRoute>
    fun isSttAvailable(recognizerComponent: String? = null): Boolean

    /** Что делать с BT-входом: маршрут/микрофон/однократный запрос разрешения. */
    fun bluetoothInputDecision(enabled: Boolean): BtInputDecision

    /** Запрос BLUETOOTH_CONNECT состоялся — повторно не спрашиваем. */
    fun markBluetoothPermissionRequested()

    fun startStt(
        language: String,
        listener: SttListener,
        recognizerComponent: String? = null,
        bluetoothInput: Boolean = true,
    ): CaptureError?

    fun stopStt()
    fun cancelStt()

    fun startRaw(maxSec: Int = VoiceListener.MAX_RAW_SEC, bluetoothInput: Boolean = true): CaptureError?
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
