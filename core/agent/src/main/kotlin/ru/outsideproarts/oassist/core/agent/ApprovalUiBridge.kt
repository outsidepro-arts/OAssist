package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ApprovalDecision

/**
 * Мост «диалог подтверждения ↔ запущенный AgentLoop» для случаев, когда диалог
 * показывает отдельная активность (notification-режим оверлея): активность
 * отвечает по [resolve], сервис держит резолвер на время паузы цикла.
 */
object ApprovalUiBridge {
    private val resolvers = java.util.concurrent.ConcurrentHashMap<String, (ApprovalDecision) -> Boolean>()

    fun register(callId: String, resolve: (ApprovalDecision) -> Boolean) {
        resolvers[callId] = resolve
    }

    /** `false` — резолвер уже снят (двойной ответ/отмена). */
    fun resolve(callId: String, decision: ApprovalDecision): Boolean {
        val resolver = resolvers[callId] ?: return false
        return resolver(decision)
    }

    fun clear(callId: String) {
        resolvers.remove(callId)
    }
}
