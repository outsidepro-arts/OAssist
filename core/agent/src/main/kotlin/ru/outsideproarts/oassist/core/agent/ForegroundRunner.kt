package ru.outsideproarts.oassist.core.agent

/**
 * Обёртка долгих операций в foreground-сервис (M5.3, тип `specialUse`).
 * Реализация живёт в app-модуле (нужен Context сервиса); здесь — интерфейс,
 * чтобы `core/agent` не зависел от `app`. Дефолт — сквозной прогон без сервиса.
 * (Обычный интерфейс: `fun interface` запрещает type-параметры у метода.)
 */
interface ForegroundRunner {
    suspend fun <T> run(task: suspend () -> T): T
}

/** Дефолт: без foreground-сервиса (тесты, превью, metadata-списки). */
class NoopForegroundRunner : ForegroundRunner {
    override suspend fun <T> run(task: suspend () -> T): T = task()
}
