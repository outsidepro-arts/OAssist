package ru.outsideproarts.oassist.core.a11y

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Канал объявлений для экранных чтецов.
 *
 * Используется двумя независимыми контентами:
 *  - ответ агента при [ru.outsideproarts.oassist.core.model.SpeechOutputChannel.SCREEN_READER]
 *    — каждое завершённое предложение публикуется отдельно (зеркалит потоковую TTS);
 *  - нарратив работы агента (размышления, вызовы инструментов) — отдельным тумблером.
 */
interface Announcer {
    fun announce(text: String, assertive: Boolean = false)
}

class FlowAnnouncer : Announcer {
    private val _utterances = MutableSharedFlow<Utterance>(extraBufferCapacity = 64)
    val utterances: SharedFlow<Utterance> = _utterances.asSharedFlow()

    override fun announce(text: String, assertive: Boolean) {
        if (text.isBlank()) return
        _utterances.tryEmit(Utterance(text.trim(), assertive))
    }
}

data class Utterance(
    val text: String,
    val assertive: Boolean,
)
