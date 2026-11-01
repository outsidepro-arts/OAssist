package ru.outsideproarts.oassist.core.data.prefs

/**
 * Мост к [SettingsDataStore] для `feature:*` без зависимости от `app` (как
 * `ChatHandle`): регистрация в `AppGraph`, получение — `get()`. Null — слой
 * данных не поднят (юнит-тесты, ранний старт) — вызывающие работают
 * со значениями по умолчанию без сохранения, не падают.
 */
object SettingsHandle {
    @Volatile
    private var settings: SettingsDataStore? = null

    fun register(settings: SettingsDataStore) {
        this.settings = settings
    }

    fun get(): SettingsDataStore? = settings
}
