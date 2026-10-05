package ru.outsideproarts.oassist.core.provider

import kotlinx.serialization.json.Json

/**
 * Общий lenient-парсер ответов провайдеров. Реальные ответы (Zen, OpenCode, прокси)
 * содержат поля вне DTO (`created`, `cost`, `limit` и т.п.) — строгий Json падает на них,
 * поэтому незнакомые ключи игнорируются.
 */
internal val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
