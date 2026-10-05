package ru.outsideproarts.oassist.core.model

/**
 * Решение пользователя по запросу подтверждения опасного действия.
 * Используется связкой `AgentLoop` (пауза) ↔ UI (диалог).
 */
enum class ApprovalDecision {
    /** Выполнить один раз. */
    ALLOW_ONCE,

    /** Выполнить и больше не спрашивать для этого инструмента. */
    ALWAYS_ALLOW,

    /** Отклонить выполнение. */
    DENY,
}

/**
 * Запрос подтверждения на выполнение инструмента.
 *
 * @param callId идентификатор вызова (совпадает с [id] тул-чонка).
 * @param toolName имя инструмента из каталога.
 * @param argumentsJson аргументы вызова как JSON-строка (для логов/отладки, без секретов).
 * @param summary человекочитаемое описание действия для диалога (уже локализовано вызывающей стороной).
 * @param risk уровень риска инструмента на момент вызова.
 */
data class ApprovalRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val summary: String,
    val risk: ActionRisk,
)
