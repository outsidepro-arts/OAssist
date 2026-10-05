package ru.outsideproarts.oassist.core.agent

/**
 * События цикла для нарратива (таймлайн + объявления скринридера).
 * Методы с дефолтной пустой реализацией — цикл работает и без слушателя.
 * Вызываются из suspend-контекста стрима, блокировать запрещено.
 */
interface AgentEvents {
    suspend fun onToolCall(toolName: String, argumentsJson: String) {}

    suspend fun onToolResult(toolName: String, output: String) {}
}
