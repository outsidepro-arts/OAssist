package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ChatMessage

/** Заметка модели о вырезанном аудио (как [IMAGE_REMOVED_NOTE] — идёт в контекст, не в UI). */
const val AUDIO_REMOVED_NOTE = "[голосовое сообщение удалено из истории]"

/**
 * Аудио в истории для модели: WAV ~3.8 МБ/мин base64 — держим только у
 * последнего голосового сообщения, старые голосовые реплики уходят текстом.
 * Реплика без текста получает [AUDIO_REMOVED_NOTE], чтобы не оставить пустой ход.
 */
fun trimHistoryAudio(messages: List<ChatMessage>): List<ChatMessage> {
    val lastAudioIndex = messages.indexOfLast { it.audio != null }
    return messages.mapIndexed { index, message ->
        if (message.audio == null || index == lastAudioIndex) {
            message
        } else {
            message.copy(audio = null, content = message.content.ifBlank { AUDIO_REMOVED_NOTE })
        }
    }
}
