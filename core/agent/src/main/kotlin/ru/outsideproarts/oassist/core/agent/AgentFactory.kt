package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.StateFlow
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.UsageTotalsState
import ru.outsideproarts.oassist.core.provider.ModelClient

/**
 * Фабрика агентов для фич-модулей. Реализуется в `app` (через AppGraph),
 * регистрируется через [register]. Позволяет `feature:*` создавать агента
 * без прямой зависимости от `app`.
 */
interface AgentFactory {
    suspend fun modeConfig(mode: AssistMode): ModeConfig
    suspend fun providerForMode(mode: AssistMode): ProviderProfile?

    /** Клиент без агентского цикла для вспомогательных запросов, например названия чата. */
    suspend fun modelClientFor(profile: ProviderProfile): ModelClient

    /** Учёт вспомогательных запросов без событий нарратива агента. */
    suspend fun recordModelUsage(usage: ProviderUsage) {}

    /** Suspend: читает снапшот настроек инструментов из DataStore. */
    suspend fun agentFor(profile: ProviderProfile, mode: AssistMode): AgentLoop

    /** Закончилась сессия режима (диалог/панель) — забыть «разрешено на текущую сессию». */
    fun clearSessionApprovals(mode: AssistMode)

    /** Итоги использования за жизнь процесса (in-memory) — строка «Всего» в ходе работы. */
    fun usageTotals(): StateFlow<UsageTotalsState>

    /** Конец сессии (закрытие оверлея/чата/шаринга) — обнулить итоги. Дефолт — no-op (фейки в тестах). */
    fun resetUsageTotals() {}

    /**
     * Инструкции режима и отдельный контекст данных для запроса.
     */
    suspend fun systemPromptFor(
        mode: AssistMode,
        screenContext: String? = null,
        sharedContent: String? = null,
    ): RuntimePrompt

    companion object {
        @Volatile
        private var instance: AgentFactory? = null

        fun register(factory: AgentFactory) {
            instance = factory
        }

        fun get(): AgentFactory = checkNotNull(instance) {
            "AgentFactory не зарегистрирован. Вызовите AgentFactory.register() в AppGraph.onCreate"
        }
    }
}
