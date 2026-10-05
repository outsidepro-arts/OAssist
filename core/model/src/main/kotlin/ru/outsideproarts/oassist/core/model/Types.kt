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

/** Семейство провайдера LLM/агента. */
@Serializable(with = ProviderKindSerializer::class)
enum class ProviderKind {
    OPENAI_COMPAT,
    ANTHROPIC,
    GEMINI,
    /** OpenCode Zen / Go: OpenAI-совместимый API + заголовки `x-opencode-*`. */
    OPENCODE_ZEN,
}

/**
 * Сериализация [ProviderKind] с миграцией: легаси-значение `OPENCODE` (клиент
 * `opencode serve`, удалён) читается как [ProviderKind.OPENCODE_ZEN].
 */
object ProviderKindSerializer : KSerializer<ProviderKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ru.outsideproarts.oassist.core.model.ProviderKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProviderKind {
        val raw = decoder.decodeString()
        return when (raw) {
            "OPENCODE" -> ProviderKind.OPENCODE_ZEN
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

/** Уровень риска действия — управляет подтверждением. */
enum class ActionRisk {
    AUTO,
    ASK,
    ALWAYS_ASK,
}
