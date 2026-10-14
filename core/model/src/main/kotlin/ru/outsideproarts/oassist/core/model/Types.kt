package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
 * (см. [ProviderKind.OPENCODE]), у которых модели одного списка обслуживаются
 * разными протоколами; простой профиль протокола задан его [ProviderKind].
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

/** Верхний уровень провайдера: простой протокол или агрегатор с несколькими. */
@Serializable(with = ProviderKindSerializer::class)
enum class ProviderKind {
    /** OpenAI Chat Completions — один протокол на весь профиль. */
    OPENAI_COMPAT,
    /** Anthropic Messages — один протокол на весь профиль. */
    ANTHROPIC,
    /** Gemini generateContent — один протокол на весь профиль. */
    GEMINI,
    /** OpenAI Responses API — один протокол на весь профиль. */
    OPENAI_RESPONSES,
    /**
     * OpenCode Zen / Go: агрегатор — один baseUrl, а протокол выбирается
     * по модели (`/chat/completions`, `/responses`, `/messages`, Gemini-пути)
     * + заголовки `x-opencode-*`.
     */
    OPENCODE,
}

/**
 * Сериализация [ProviderKind] с миграцией: легаси `OPENCODE_ZEN` (единый
 * OpenAI-совместимый клиент Zen/Go) и старейший `OPENCODE` (клиент
 * `opencode serve`, удалён) читаются как [ProviderKind.OPENCODE].
 */
object ProviderKindSerializer : KSerializer<ProviderKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ru.outsideproarts.oassist.core.model.ProviderKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProviderKind {
        val raw = decoder.decodeString()
        return when (raw) {
            "OPENCODE", "OPENCODE_ZEN" -> ProviderKind.OPENCODE
            else -> ProviderKind.entries.firstOrNull { it.name == raw }
                ?: throw SerializationException("Unknown ProviderKind: $raw")
        }
    }

    override fun serialize(encoder: Encoder, value: ProviderKind) {
        encoder.encodeString(value.name)
    }
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


