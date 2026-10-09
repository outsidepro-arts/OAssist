package ru.outsideproarts.oassist.core.agent

/**
 * События цикла для нарратива (таймлайн + объявления скринридера).
 * Методы с дефолтной пустой реализацией — цикл работает и без слушателя.
 * Вызываются из suspend-контекста стрима, блокировать запрещено.
 */
interface AgentEvents {
    /**
     * @param argumentsJson — сырой JSON аргументов (для timeline)
     * @param summary — human-readable описание (из `describeForConfirmation`, для речи)
     */
    suspend fun onToolCall(toolName: String, argumentsJson: String, summary: String = "") {}

    suspend fun onToolResult(toolName: String, output: String, success: Boolean = true) {}
}
