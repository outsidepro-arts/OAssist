package ru.outsideproarts.oassist.core.speech

/**
 * Окно audio focus на время озвучки: первая фраза в очереди — запрос фокуса,
 * пустая очередь — отпускание. Ducking без паузы (`willPauseWhenDucked=false`):
 * чужие приложения только убавляются, пока говорит ассистент.
 */
class TtsFocusWindow(
    private val requestFocus: () -> Unit,
    private val abandonFocus: () -> Unit,
) {
    private var pending = 0
    private var held = false

    fun onUtteranceStarted() {
        pending += 1
        if (!held) {
            held = true
            requestFocus()
        }
    }

    fun onUtteranceFinished() {
        pending = (pending - 1).coerceAtLeast(0)
        releaseIfIdle()
    }

    /** Принудительный сброс (stop/shutdown/смена канала) — фокус отпускается немедленно. */
    fun reset() {
        pending = 0
        releaseIfIdle()
    }

    private fun releaseIfIdle() {
        if (pending == 0 && held) {
            held = false
            abandonFocus()
        }
    }
}
