package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.provider.ChatChunk

/**
 * Одна запись полотна «Ход работы»: вызов инструмента и его результат.
 * [output] null — вызов ещё без результата (стрим идёт).
 */
data class AgentToolEvent(
    val name: String,
    val output: String? = null,
    val isError: Boolean = false,
    val images: List<ru.outsideproarts.oassist.core.model.ImagePart> = emptyList(),
)

/** Три проекции ответа агента — раздельные сущности для UI и синтеза. */
data class AgentMessageParts(
    /** Размышление/комментарий («ищу контакт…») — в свёрнутое полотно, в синтез опционально. */
    val thought: String = "",
    /** Вызовы инструментов с результатами — в свёрнутое полотно, в синтез никогда. */
    val tools: List<AgentToolEvent> = emptyList(),
    /** Основной ответ — всегда видим, идёт в синтез. */
    val answer: String = "",
)

/**
 * Собирает [AgentMessageParts] из потока [ChatChunk].
 * Чистый Kotlin (JVM-тестируем). Наследие `[tool]`/`[tool: result]` в тексте
 * сюда не попадает: AgentLoop эмитит их отдельными чанками.
 */
class AgentMessageAssembler {
    private val thoughtSb = StringBuilder()
    private val answerSb = StringBuilder()
    private val toolEvents = mutableListOf<AgentToolEvent>()

    fun onChunk(chunk: ChatChunk) {
        when (chunk) {
            is ChatChunk.Content -> {
                if (chunk.isToolComment) thoughtSb.append(chunk.delta)
                else answerSb.append(chunk.delta)
            }
            is ChatChunk.ToolCall -> toolEvents.add(AgentToolEvent(name = chunk.name))
            is ChatChunk.ToolResult -> {
                val pending = toolEvents.indexOfLast { it.name == chunk.name && it.output == null }
                val event = AgentToolEvent(
                    name = chunk.name,
                    output = chunk.output,
                    isError = chunk.isError,
                    images = chunk.images,
                )
                if (pending >= 0) toolEvents[pending] = event else toolEvents.add(event)
            }
            is ChatChunk.ApprovalNeeded,
            is ChatChunk.Finish,
            // Мысли — отдельный поток (WorkTimelineBuilder в UI-поверхностях).
            is ChatChunk.Reasoning,
            -> Unit
        }
    }

    fun thoughtText(): String = thoughtSb.toString()

    fun answerText(): String = answerSb.toString()

    fun snapshot(): AgentMessageParts =
        AgentMessageParts(
            thought = thoughtSb.toString(),
            tools = toolEvents.toList(),
            answer = answerSb.toString(),
        )
}
