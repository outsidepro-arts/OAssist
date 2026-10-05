package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Нарратив работы агента: размышления, вызовы инструментов.
 * Только через экранный чтец (announceForAccessibility), отдельным тумблером.
 * Одновременно отображается визуально — сворачиваемый timeline.
 */
class Narrator {

    var enabled: Boolean = true

    private val _events = MutableSharedFlow<NarrativeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NarrativeEvent> = _events.asSharedFlow()

    private val _timeline = MutableSharedFlow<TimelineEntry>(extraBufferCapacity = 64)
    val timeline: SharedFlow<TimelineEntry> = _timeline.asSharedFlow()

    suspend fun onToolCall(toolName: String, arguments: String) {
        if (!enabled) return
        _events.emit(NarrativeEvent.ToolCall(toolName))
        _timeline.emit(TimelineEntry.ToolCall(toolName, arguments, System.currentTimeMillis()))
    }

    suspend fun onToolResult(toolName: String, output: String) {
        if (!enabled) return
        _events.emit(NarrativeEvent.ToolResult(toolName, output))
        _timeline.emit(TimelineEntry.ToolResult(toolName, output, System.currentTimeMillis()))
    }

    suspend fun onThought(text: String) {
        if (!enabled) return
        _events.emit(NarrativeEvent.Thought(text))
        _timeline.emit(TimelineEntry.Thought(text, System.currentTimeMillis()))
    }

    suspend fun onUserMessage(text: String) {
        _timeline.emit(TimelineEntry.UserMessage(text, System.currentTimeMillis()))
    }

    suspend fun onAgentMessage(text: String) {
        _timeline.emit(TimelineEntry.AgentMessage(text, System.currentTimeMillis()))
    }

    sealed interface NarrativeEvent {
        data class Thought(val text: String) : NarrativeEvent
        data class ToolCall(val toolName: String) : NarrativeEvent
        data class ToolResult(val toolName: String, val output: String) : NarrativeEvent
    }

    sealed interface TimelineEntry {
        val timestamp: Long

        data class UserMessage(val text: String, override val timestamp: Long) : TimelineEntry
        data class AgentMessage(val text: String, override val timestamp: Long) : TimelineEntry
        data class Thought(val text: String, override val timestamp: Long) : TimelineEntry
        data class ToolCall(val toolName: String, val arguments: String, override val timestamp: Long) : TimelineEntry
        data class ToolResult(val toolName: String, val output: String, override val timestamp: Long) : TimelineEntry
    }
}
