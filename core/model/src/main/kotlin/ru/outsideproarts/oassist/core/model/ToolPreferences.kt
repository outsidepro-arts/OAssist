package ru.outsideproarts.oassist.core.model

/**
 * Пользовательские настройки инструментов агента.
 * Новые инструменты по умолчанию включены; переопределения хранятся только
 * для изменённых — поэтому оба поля содержат лишь дельту от дефолтов.
 *
 * @param disabled имена выключенных инструментов.
 * @param askOverrides пер-тул «спрашивать перед выполнением»: true — минимум ASK,
 * false — всегда AUTO без вопросов. Нет записи — дефолтный риск инструмента.
 */
data class ToolPreferences(
    val disabled: Set<String> = emptySet(),
    val askOverrides: Map<String, Boolean> = emptyMap(),
    /** Лимит раундов вызова инструментов в одном ходе агента; [UNLIMITED_MAX_TOOL_ROUNDS] — без лимита. */
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {
    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 15

        /** Без лимита: ход завершается только самим агентом (Finish без вызовов). */
        const val UNLIMITED_MAX_TOOL_ROUNDS = 0
    }
}
