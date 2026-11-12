package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ImagePart

/**
 * Заметка на историческом сообщении вместо переехавших образов: модель должна
 * знать, что изображение существует и доставлено с текущим запросом.
 */
const val PINNED_IMAGE_MOVED_NOTE = "[закреплённое изображение доставлено с текущим запросом]"

/** Источник переехавших образов для конверта текущего сообщения. */
internal data class HoistedPinnedSource(
    /** Адрес сообщения-источника (`<id хода>:<индекс>`). */
    val id: String,
    /** Заметка закрепления: её модель видит рядом с образами референса. */
    val note: String? = null,
    /** Сколько образов источника переехало в текущее сообщение. */
    val imageCount: Int,
)

/** Результат переезда закреплённых образов в текущее сообщение. */
internal data class HoistedPinnedImages(
    /** Список сообщений для сборки запроса (копии; исходный транскрипт не изменяется). */
    val messages: List<ChatMessage>,
    /** Источники переехавших образов в порядке переезда (для конверта текущего сообщения). */
    val sources: List<HoistedPinnedSource>,
)

/**
 * Закреплённые образы истории доставляются в текущем сообщении запроса:
 * свежая позиция гарантирует, что модель увидит референс (старые изображения
 * в длинной истории теряются из внимания и могут отбрасываться провайдером).
 * Историческое сообщение остаётся на месте с заметкой [PINNED_IMAGE_MOVED_NOTE].
 * По стоимости запрос не меняется: образ и так доставлялся каждый ход.
 * Конверт текущего сообщения получает адреса источников, заметки закрепления и
 * число образов — чтобы модель связала референсы с их сообщениями (см.
 * pinnedImageFields).
 *
 * Переезжает только история (index < currentUserIndex) с адресом [ChatMessage.id]:
 * сообщения текущего хода и само текущее сообщение уже в свежей позиции, а без
 * адреса источник нельзя указать в `pinnedImages` конверта — метаданные и образы
 * разъехались бы. Аудио источника (голосовое сообщение — не образ) и сообщения
 * без закрепления не трогает. Pure.
 */
internal fun hoistPinnedImages(messages: List<ChatMessage>, currentUserIndex: Int): HoistedPinnedImages {
    require(currentUserIndex in messages.indices) {
        "currentUserIndex=$currentUserIndex вне сообщений (${messages.size})"
    }
    val hoisted = mutableListOf<ImagePart>()
    val sources = mutableListOf<HoistedPinnedSource>()
    val out = messages.mapIndexed { index, message ->
        val sourceId = message.id
        val pin = message.pin
        if (index >= currentUserIndex || sourceId == null || pin == null || message.images.isEmpty()) {
            message
        } else {
            hoisted += message.images
            sources += HoistedPinnedSource(id = sourceId, note = pin.note, imageCount = message.images.size)
            val note = if (message.content.isEmpty()) {
                PINNED_IMAGE_MOVED_NOTE
            } else {
                message.content + "\n" + PINNED_IMAGE_MOVED_NOTE
            }
            message.copy(images = emptyList(), content = note)
        }
    }.toMutableList()
    if (hoisted.isNotEmpty()) {
        val current = out[currentUserIndex]
        // Референсы — первыми: модель сверяет с ними присланное.
        out[currentUserIndex] = current.copy(images = hoisted + current.images)
    }
    return HoistedPinnedImages(out, sources)
}
