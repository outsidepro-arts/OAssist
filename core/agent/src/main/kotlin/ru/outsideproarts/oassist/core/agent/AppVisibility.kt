package ru.outsideproarts.oassist.core.agent

/**
 * Живёт ли у процесса resumed-активность (передний план приложения).
 * Регистрируется `ActivityLifecycleCallbacks` в Application; тулы используют,
 * чтобы не выдавать ложную ошибку фонового запуска из чата/шаринга.
 */
object AppVisibility {

    @Volatile
    private var resumedCount = 0

    val isForeground: Boolean
        get() = resumedCount > 0

    fun onActivityResumed() {
        resumedCount++
    }

    fun onActivityPaused() {
        if (resumedCount > 0) resumedCount--
    }

    /** Только для тестов. */
    internal fun resetForTest() {
        resumedCount = 0
    }
}
