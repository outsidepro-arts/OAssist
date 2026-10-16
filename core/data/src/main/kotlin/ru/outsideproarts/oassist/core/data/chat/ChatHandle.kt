package ru.outsideproarts.oassist.core.data.chat

/**
 * Мост к [ChatRepository] для `feature:*` без зависимости от `app` (как
 * `SpeechHandle`): регистрация в `AppGraph`, получение — `get()`.
 * Null — слой данных не поднят (юнит-тесты, ранний старт) — вызывающие
 * деградируют без сохранения, не падают.
 */
object ChatHandle {
    @Volatile
    private var repository: ChatRepository? = null

    fun register(repository: ChatRepository) {
        this.repository = repository
    }

    fun get(): ChatRepository? = repository
}
