package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ProviderProfile

/**
 * Фабрика агентов для фич-модулей. Реализуется в `app` (через AppGraph),
 * регистрируется через [register]. Позволяет `feature:*` создавать агента
 * без прямой зависимости от `app`.
 */
interface AgentFactory {
    suspend fun modeConfig(mode: AssistMode): ModeConfig
    suspend fun providerForMode(mode: AssistMode): ProviderProfile?

    /** Suspend: читает снапшот настроек инструментов из DataStore. */
    suspend fun agentFor(profile: ProviderProfile, mode: AssistMode): AgentLoop

    /** Закончилась сессия режима (диалог/панель) — забыть «разрешено на текущую сессию». */
    fun clearSessionApprovals(mode: AssistMode)

    /**
     * Готовый системный промпт режима: шаблон (свой или дефолт из assets)
     * + память + включённые навыки + контекст экрана/шаринга.
     * Единая точка — вместо ручной сборки в каждом UI.
     */
    suspend fun systemPromptFor(
        mode: AssistMode,
        screenContext: String? = null,
        sharedContent: String? = null,
    ): String

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
