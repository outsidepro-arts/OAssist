package ru.outsideproarts.oassist.core.model

/**
 * Пользовательские настройки инструментов агента.
 * Новые инструменты по умолчанию включены; хранится лишь дельта от дефолтов.
 *
 * @param disabled имена выключенных инструментов.
 */
data class ToolPreferences(
    val disabled: Set<String> = emptySet(),
    /** Лимит раундов вызова инструментов в одном ходе агента; [UNLIMITED_MAX_TOOL_ROUNDS] — без лимита. */
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {
    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 15

        /** Без лимита: ход завершается только самим агентом (Finish без вызовов). */
        const val UNLIMITED_MAX_TOOL_ROUNDS = 0
    }
}
