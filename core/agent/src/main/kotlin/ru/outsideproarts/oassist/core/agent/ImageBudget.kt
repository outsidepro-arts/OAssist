package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.provider.ChatMessage

/** Образы вырезаны из истории: модель должна знать, что картинка была. */
const val IMAGE_REMOVED_NOTE = "[изображение удалено из истории]"

/** У модели нет флага VISION — образы не переданы. */
const val IMAGE_DISABLED_NOTE = "\n[изображение не передано: у модели нет поддержки изображений]"

/** Сколько последних сообщений с образами держим в истории (base64 жрёт контекст). */
const val MAX_IMAGE_MESSAGES = 4

/**
 * Бюджет образов: остаются только последние [maxImages] сообщений с образами,
 * у старых образы снимаются с пометкой в тексте. Pure — тестируется на JVM.
 */
fun enforceImageBudget(messages: List<ChatMessage>, maxImages: Int = MAX_IMAGE_MESSAGES): List<ChatMessage> {
    var remaining = maxImages
    val out = ArrayList<ChatMessage>(messages.size)
    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        if (msg.images.isEmpty()) {
            out.add(msg)
            continue
        }
        if (remaining > 0) {
            remaining--
            out.add(msg)
        } else {
            val note = if (msg.content.isEmpty()) IMAGE_REMOVED_NOTE else msg.content + "\n" + IMAGE_REMOVED_NOTE
            out.add(msg.copy(images = emptyList(), content = note))
        }
    }
    out.reverse()
    return out
}