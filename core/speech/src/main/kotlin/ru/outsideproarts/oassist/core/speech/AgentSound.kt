package ru.outsideproarts.oassist.core.speech

/**
 * Событийные звуки агента (ogg в `res/raw`, короткие и тихие).
 * Трекинга повторов нет: звуки подобраны неназойливыми и играют
 * параллельно, не мешая событиям.
 */
enum class AgentSound(val resName: String) {
    ANSWER_READY("answer_ready"),
    ERROR("error"),
    MSG_SEND("msg_send"),
    OVERLAY_START("overlay_start"),
    OVERLAY_STOP("overlay_stop"),
    PERMISSION_DENIED("permission_denied"),
    PERMISSION_REQUEST("permission_request"),
    THINKING("thinking"),
    TOOL_CALL("tool_call"),
}

/**
 * Точка доступа к событийным звукам для `feature:*` без зависимости от `app`.
 * Регистрируется в `AppGraph` (адаптер над [AgentSounds]),
 * зеркалит паттерн [SpeechHandle].
 */
interface SoundHandle {
    /** Проиграть звук fire-and-forget (неблокирующе, параллельно остальному). */
    fun play(sound: AgentSound)

    /** Проиграть, только если сопровождение включено в активном режиме. */
    fun playIfEnabled(sound: AgentSound, enabled: Boolean) {
        if (enabled) play(sound)
    }

    /**
     * Проиграть `overlay_start` и дождаться конца (гейт «сначала звук,
     * затем STT»). Дефолт — no-op для фейков; прод-реализация ждёт
     * с таймаутом (см. [AgentSounds.playOverlayStartAndAwait]).
     */
    suspend fun playOverlayStartAndAwait() {}

    companion object {
        @Volatile
        private var instance: SoundHandle? = null

        fun register(handle: SoundHandle) {
            instance = handle
        }

        /** Null — звуки недоступны (молчим, не падаем). */
        fun get(): SoundHandle? = instance
    }
}
