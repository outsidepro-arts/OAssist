package ru.outsideproarts.oassist.core.speech

/**
 * Окно audio focus на время слушания (механика голосового ввода GBoard): фокус берётся
 * ДО старта захвата — TTS (включая TalkBack) получает LOSS_TRANSIENT и прерывает речь,
 * распознавание получает чистый аудиопуть; музыка встаёт на паузу и возвращается на
 * отпускании. Держится на все retry-попытки одной пользовательской сессии.
 */
class ListenFocusWindow(
    private val requestFocus: () -> Unit,
    private val abandonFocus: () -> Unit,
) {
    private var held = false

    @Synchronized
    fun open() {
        if (held) return
        held = true
        requestFocus()
    }

    @Synchronized
    fun close() {
        if (!held) return
        held = false
        abandonFocus()
    }
}
