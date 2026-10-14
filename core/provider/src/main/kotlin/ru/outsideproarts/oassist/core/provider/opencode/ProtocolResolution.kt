package ru.outsideproarts.oassist.core.provider.opencode

import ru.outsideproarts.oassist.core.model.ModelProtocol

/**
 * Резолв проводного протокола модели для агрегаторов (OpenCode Zen/Go и им
 * подобные): один список моделей обслуживается разными эндпоинтами.
 *
 * Цепочка: ручной override пользователя → маркер каталога (`provider.npm`
 * из models.dev, см. `ModelsDevCatalog`) → эвристика по id → chat/completions.
 * Живой `/models` агрегаторов тип эндпоинта не отдаёт — без этой цепочки
 * Claude/Gemini/GPT уходят не на те пути (400).
 */
object ProtocolResolution {

    /**
     * Полный резолв модели. [override] — ручной выбор из UI (каталог),
     * [catalogNpm] — `provider.npm` этой модели из models.dev (null — нет данных).
     */
    fun resolve(modelId: String, override: ModelProtocol?, catalogNpm: String?): ModelProtocol {
        override?.let { return it }
        catalogNpm?.let { npm -> npmToProtocol(npm)?.let { return it } }
        return heuristic(modelId)
    }

    /**
     * Маппинг AI SDK-пакета models.dev на проводной протокол.
     * `@ai-sdk/mistral` — провод chat/completions (Mistral-совместимый);
     * неизвестный пакет — null (дальше по цепочке).
     */
    fun npmToProtocol(npm: String): ModelProtocol? = when (npm) {
        "@ai-sdk/openai" -> ModelProtocol.OPENAI_RESPONSES
        "@ai-sdk/anthropic" -> ModelProtocol.ANTHROPIC_MESSAGES
        "@ai-sdk/google" -> ModelProtocol.GEMINI_GENERATE
        "@ai-sdk/openai-compatible", "@ai-sdk/mistral" -> ModelProtocol.OPENAI_COMPLETIONS
        else -> null
    }

    /**
     * Эвристика по id — только для однозначных семейств:
     * `claude*` → messages, `gemini*` → gemini, `gpt*`/`grok*`/`muse*` → responses.
     * Смешанные семейства (qwen: max → completions, flash → messages; minimax:
     * Zen → completions, Go → messages) честно уходят в chat/completions —
     * дефолт `@ai-sdk/openai-compatible` на уровне провайдера models.dev;
     * остальное покрывают каталог и ручной override.
     */
    fun heuristic(modelId: String): ModelProtocol {
        val id = modelId.lowercase()
        return when {
            id.startsWith("claude") -> ModelProtocol.ANTHROPIC_MESSAGES
            id.startsWith("gemini") -> ModelProtocol.GEMINI_GENERATE
            id.startsWith("gpt") || id.startsWith("grok") || id.startsWith("muse") ->
                ModelProtocol.OPENAI_RESPONSES
            else -> ModelProtocol.OPENAI_COMPLETIONS
        }
    }
}
