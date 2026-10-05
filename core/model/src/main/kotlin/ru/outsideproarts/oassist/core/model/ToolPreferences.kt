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
)
