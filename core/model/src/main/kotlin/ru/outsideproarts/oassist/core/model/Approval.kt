package ru.outsideproarts.oassist.core.model

/**
 * Решение пользователя по запросу подтверждения действия.
 * Используется связкой `AgentLoop` (пауза) ↔ UI (диалог).
 */
enum class ApprovalDecision {
    /** Выполнить один раз. */
    ALLOW_ONCE,

    /** Выполнить и не спрашивать эту группу до конца сессии (диалога/панели). */
    ALLOW_SESSION,

    /** Выполнить и запомнить «разрешено» для группы навсегда. */
    ALWAYS_ALLOW,

    /** Отклонить выполнение (один раз). */
    DENY,

    /** Отклонить и запомнить «запретить» для группы навсегда. */
    ALWAYS_DENY,
}

/** Состояние группы разрешений: спрашивать / разрешено / запрещено. */
enum class ApprovalState {
    ASK,
    ALLOW,
    DENY,
}

/**
 * Пресет в «Безопасности»: чему быть в группах чтения/записи.
 * Выбор переписывает состояния категорий; MCP-группы трогают только
 * [ALLOW_ALL]/[DENY_ALL] (тип доступа у них неизвестен заранее).
 */
enum class AskPreset {
    /** Спрашивать на чтение: чтение — спросить, запись — разрешить. */
    ASK_READ,

    /** Спрашивать на запись: запись — спросить, чтение — разрешить. */
    ASK_WRITE,

    /** Спрашивать и на чтение, и на запись (дефолт). */
    ASK_BOTH,

    /** Разрешить все (включая MCP). */
    ALLOW_ALL,

    /** Запретить все (включая MCP). */
    DENY_ALL;

    /**
     * Состояние группы с типом доступа [access] по этому пресету.
     * `access == null` (MCP/прочее) подчиняется только [ALLOW_ALL]/[DENY_ALL],
     * иначе остаётся спрашивать.
     */
    fun stateFor(access: ToolAccess?): ApprovalState = when {
        this == ALLOW_ALL -> ApprovalState.ALLOW
        this == DENY_ALL -> ApprovalState.DENY
        access == null -> ApprovalState.ASK
        this == ASK_READ -> if (access == ToolAccess.READ) ApprovalState.ASK else ApprovalState.ALLOW
        this == ASK_WRITE -> if (access == ToolAccess.WRITE) ApprovalState.ASK else ApprovalState.ALLOW
        else -> ApprovalState.ASK
    }

    companion object {
        val DEFAULT: AskPreset = ASK_BOTH
    }
}

/**
 * Запрос подтверждения на выполнение инструмента.
 *
 * @param callId идентификатор вызова (совпадает с `id` тул-чонка).
 * @param toolName имя инструмента из каталога.
 * @param argumentsJson аргументы вызова как JSON-строка (для логов/отладки, без секретов).
 * @param summary человекочитаемое описание действия для диалога (уже локализовано вызывающей стороной).
 * @param groupKey ключ группы разрешений ([ToolGroups]): решение действует на всю группу.
 * @param screenLocked экран был заблокирован в момент запроса — UI показывает подпись,
 * модель получает пометку в результате.
 */
data class ApprovalRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val summary: String,
    val groupKey: String,
    val screenLocked: Boolean = false,
)
