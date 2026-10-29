package ru.outsideproarts.oassist.core.model

/** Язык интерфейса и речи. Русский — приоритетный и дефолтный. */
enum class AppLanguage {
    SYSTEM,
    RUSSIAN,
    ENGLISH;

    companion object {
        val DEFAULT: AppLanguage = RUSSIAN
    }
}

/** Режим работы агента: свой провайдер, модель и initial prompt. */
enum class AssistMode {
    CHAT,
    ASSISTANT,
    SHARE,
}

/**
 * Проводной протокол эндпоинта модели — какому API говорим. Нужен агрегаторам
 * (см. [ProviderPreset.OPENCODE]), у которых модели одного списка обслуживаются
 * разными протоколами; простой профиль задан фиксом [ProviderProfile.protocol].
 */
enum class ModelProtocol {
    /** OpenAI Chat Completions: `POST {base}/chat/completions`. */
    OPENAI_COMPLETIONS,

    /** OpenAI Responses API: `POST {base}/responses`. */
    OPENAI_RESPONSES,

    /** Anthropic Messages: `POST {base}/messages` (без удвоения `/v1`). */
    ANTHROPIC_MESSAGES,

    /** Gemini generateContent: `POST {base}/models/{id}:streamGenerateContent`. */
    GEMINI_GENERATE,
}

/**
 * Пресет провайдера: готовый рецепт известного провайдера (Base URL, протокол,
 * каталог и авторизация — под капотом) либо «Своя настройка» с открытыми полями.
 * Новый нестандартный провайдер — новый рецепт поверх общих механизмов
 * (роутинг протокола, каталог, auth-схемы), а не новый wire-протокол.
 * Значения полей профиля пишутся явно: пресет лишь заполняет дефолты,
 * смена пресета не затирает учётные данные.
 */
enum class ProviderPreset {
    OPENAI,
    GOOGLE,
    ANTHROPIC,
    OPENCODE,
    CUSTOM;

    /** Базовый URL по умолчанию; null — задаётся пользователем (CUSTOM). */
    fun defaultBaseUrl(): String? = when (this) {
        OPENAI -> "https://api.openai.com/v1"
        GOOGLE -> GoogleApiVersion.BETA.baseUrl
        ANTHROPIC -> "https://api.anthropic.com/v1"
        OPENCODE -> OpenCodeEndpoint.ZEN.baseUrl
        CUSTOM -> null
    }

    /** Каталог нового профиля: у пресетов он известен и всегда `{base}/models`. */
    fun defaultFetchScheme(): ModelFetchScheme = when (this) {
        CUSTOM -> ModelFetchScheme.MODELS
        else -> ModelFetchScheme.MODELS
    }

    /** Авторизация нового профиля: у пресетов нативная — один API-ключ. */
    fun defaultAuthScheme(): AuthScheme = AuthScheme.NATIVE
}

/**
 * Версия API Google Gemini — выбор пресета [ProviderPreset.GOOGLE];
 * под капотом задаёт Base URL (`.../v1beta` либо `.../v1`).
 */
enum class GoogleApiVersion(val baseUrl: String) {
    BETA("https://generativelanguage.googleapis.com/v1beta"),
    V1("https://generativelanguage.googleapis.com/v1");

    companion object {
        fun fromBaseUrl(baseUrl: String): GoogleApiVersion =
            if (baseUrl.trimEnd('/').endsWith("/v1")) V1 else BETA
    }
}

/**
 * Эндпоинт OpenCode — выбор пресета [ProviderPreset.OPENCODE]; под капотом
 * задаёт Base URL (контракт: уже с `/v1`).
 */
enum class OpenCodeEndpoint(val baseUrl: String) {
    ZEN("https://opencode.ai/zen/v1"),
    ZEN_GO("https://opencode.ai/zen/go/v1");

    companion object {
        fun fromBaseUrl(baseUrl: String): OpenCodeEndpoint =
            entries.firstOrNull { it.baseUrl == baseUrl.trimEnd('/') } ?: ZEN
    }
}

/**
 * Режим каталога моделей профиля. Путь един для всех API — `{base}/models`:
 * версия API (`/v1`, `/v1beta`) входит в [ru.outsideproarts.oassist.core.model.ProviderProfile.baseUrl],
 * как в документации провайдера. Формат ответа распознаётся lenient-парсером,
 * «Авто» как значения нет: выбирать нечего.
 */
enum class ModelFetchScheme {
    /** Каталог по `{base}/models`. */
    MODELS,

    /** Каталога нет: модели вводятся вручную, запрос не отправляется. */
    NONE,
}

/**
 * Схема авторизации профиля. [NATIVE] всегда key-based: нативный маппинг
 * протоколов (Bearer / x-api-key / x-goog-api-key) отправляет один API-ключ
 * разными заголовками и никогда не требует логина/пароля.
 */
enum class AuthScheme {
    /** Нативная по проводному протоколу модели (см. [ModelProtocol]). */
    NATIVE,

    /** `Authorization: Bearer <key>` на все запросы. */
    BEARER,

    /** API-ключ в заголовок [ru.outsideproarts.oassist.core.model.ProviderProfile.apiKeyHeaderName]. */
    API_KEY_HEADER,

    /** Basic: логин + пароль → `Authorization: Basic`. */
    BASIC,

    /** Без авторизации (локальные серверы). */
    NONE,
}

/** Уровень привилегий инструмента. */
enum class PrivilegeLevel {
    NONE,
    SHIZUKU,
    ROOT,
}

/** Канал озвучки ответа агента. */
enum class SpeechOutputChannel {
    OFF,
    SYSTEM_TTS,
    ONLINE_TTS,
    SCREEN_READER,
}


