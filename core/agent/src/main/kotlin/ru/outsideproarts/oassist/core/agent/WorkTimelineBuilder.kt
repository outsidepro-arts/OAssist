package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.provider.ChatChunk

/**
 * Единый накопитель «Ход работы» для UI-поверхностей (чат/оверлей/шаринг):
 * хронологический интерлив мыслей и тулов (DeepSeek-стиль) + ответ + usage.
 *
 * - `ChatChunk.Reasoning` (мысли модели) и комментарии агента
 *   (`Content.isToolComment`) — блоки [WorkEvent.Thought]; смена «рода»
 *   (reasoning ↔ комментарий) начинает новый блок.
 * - [commentText] — только комментарии агента (как раньше шли в синтез);
 *   reasoning-текст НЕ озвучивается (осознанное решение).
 * - Тул-результат заполняет последний открытым вызов с тем же именем.
 */
class WorkTimelineBuilder {

    private enum class Kind { NONE, REASONING, COMMENT }

    private var lastKind = Kind.NONE
    private val events = mutableListOf<WorkEvent>()
    private val commentSb = StringBuilder()
    private val answerSb = StringBuilder()

    var usage: ProviderUsage? = null
        private set

    val commentText: String get() = commentSb.toString()
    val answerText: String get() = answerSb.toString()
    val work: List<WorkEvent> get() = events.toList()

    val isEmpty: Boolean get() = events.isEmpty() && answerSb.isEmpty() && commentSb.isEmpty()

    /**
     * Обработать чанк.
     * @return true — полотно/ответ изменились, UI нужно опубликовать.
     */
    fun onChunk(chunk: ChatChunk): Boolean = when (chunk) {
        is ChatChunk.Reasoning -> {
            if (chunk.delta.isNotEmpty()) appendThought(chunk.delta, Kind.REASONING) else false
        }
        is ChatChunk.Content -> {
            if (chunk.isToolComment) {
                commentSb.append(chunk.delta)
                appendThought(chunk.delta, Kind.COMMENT)
            } else {
                answerSb.append(chunk.delta)
            }
            true
        }
        is ChatChunk.ToolCall -> {
            events += WorkEvent.Tool(name = chunk.name)
            true
        }
        is ChatChunk.ToolResult -> {
            val pending = events.indexOfLast {
                it is WorkEvent.Tool && it.name == chunk.name && it.output == null
            }
            val event = WorkEvent.Tool(
                name = chunk.name,
                output = chunk.output,
                isError = chunk.isError,
                images = chunk.images,
            )
            if (pending >= 0) events[pending] = event else events += event
            true
        }
        is ChatChunk.Finish -> {
            usage = chunk.usage
            true
        }
        is ChatChunk.ApprovalNeeded -> false
    }

    private fun appendThought(text: String, kind: Kind): Boolean {
        if (text.isEmpty()) return false
        val last = events.lastOrNull()
        if (last is WorkEvent.Thought && lastKind == kind) {
            events[events.size - 1] = WorkEvent.Thought(last.text + text)
        } else {
            events += WorkEvent.Thought(text)
        }
        lastKind = kind
        return true
    }
}
