package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import ru.outsideproarts.oassist.core.provider.ChatChunk

/**
 * Живая сессия диалога: стрим чанков плюс агент, который его ведёт.
 * Агент нужен UI чтобы отвечать на [ChatChunk.ApprovalNeeded]
 * через [AgentLoop.resolveApproval] — голый `Flow` этого не позволяет.
 */
data class AgentSession(
    val chunks: Flow<ChatChunk>,
    val agent: AgentLoop,
)
