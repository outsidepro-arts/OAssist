package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.Serializable

/**
 * Профиль LLM-провайдера. Секреты хранятся отдельно: в полях-ссылках — alias в `SecretStore`,
 * сами значения никогда не попадают в модель/БД/логи.
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    /** Alias API-ключа в SecretStore; null — ключ не задан. */
    val apiKeyRef: String? = null,
    /** Имя заголовка → alias его значения в SecretStore. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

/** Дополнения режима ASSISTANT. */
@Serializable
data class AssistantExtras(
    val autoSpeak: Boolean = true,
    val autoClose: Boolean = true,
    val attachScreenshot: Boolean = true,
    /** Продолжать диалог автоматически: после озвучки ответа снова включать микрофон. */
    val continueDialog: Boolean = false,
    /**
     * Показывать панель ассистента поверх экрана; false — режим уведомления:
     * окно не создаётся, сессия живёт в уведомлении (приложения не перекрываются).
     */
    val showOverlay: Boolean = true,
)

/** Дополнения режима SHARE. */
@Serializable
data class ShareExtras(
    /** Инструкция «что делать с {shared_content}»; null — решает промпт по умолчанию. */
    val sharedContentAction: String? = null,
    val showInChat: Boolean = true,
)

/**
 * Озвучка режима: у каждого режима (CHAT/ASSISTANT/SHARE) свой канал, движок,
 * голос и тембр. `enginePackage` — пакет синтезатора (`TextToSpeech.getEngines`),
 * null — системный по умолчанию. `voiceName` — имя из `voices` этого движка
 * (имена уникальны только внутри движка), null — голос по умолчанию.
 * `speakToolCalls` — озвучивать вызовы инструментов, но только комментарием
 * агента («ищу контакт…»); тип инструмента не зачитывается никогда.
 * `narrative` — рассказ о работе через экранный чтец (звук TTS не занимает).
 */
@Serializable
data class TtsConfig(
    val channel: SpeechOutputChannel = SpeechOutputChannel.SYSTEM_TTS,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val enginePackage: String? = null,
    val voiceName: String? = null,
    val speakToolCalls: Boolean = true,
    val narrative: Boolean = true,
) {
    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2f
        const val MIN_VOLUME = 0f
        const val MAX_VOLUME = 1f

        val DEFAULT = TtsConfig()
    }
}

/**
 * Движок голосового ввода режима. `MODEL_AUDIO` требует флага
 * [ModelCapability.AUDIO_IN] у выбранной модели (флаг — только ручной,
 * списки провайдеров capabilities не отдают); без флага молча уходим в STT.
 */
enum class SttEngine {
    SYSTEM,
    MODEL_AUDIO,
}

/**
 * Голосовой ввод режима: движок + язык распознавания.
 * Аудио-в-модель — WAV 16 кГц моно (см. `WavEncoder`), кап длины — в захвате.
 */
@Serializable
data class SttConfig(
    val engine: SttEngine = SttEngine.SYSTEM,
    val language: String = "ru-RU",
) {
    companion object {
        val DEFAULT = SttConfig()
    }
}

/**
 * Конфигурация режима: у каждого режима свой провайдер, модель, initial prompt,
 * озвучка и голосовой ввод. [initialPrompt] всегда хранит текст: поле экрана
 * режима предзаполняется промптом по умолчанию из assets/prompts, пустое поле
 * при сохранении заполняется им же. [initialPrompt] = null — только legacy
 * (конфиги/бэкапы до материализованных промптов), трактуется как пусто.
 * [tts]/[stt] = null означают дефолты (старые конфиги).
 */
@Serializable
data class ModeConfig(
    val mode: AssistMode,
    val providerId: String? = null,
    val modelId: String? = null,
    val initialPrompt: String? = null,
    val assistant: AssistantExtras? = null,
    val share: ShareExtras? = null,
    val tts: TtsConfig? = null,
    val stt: SttConfig? = null,
    /** Звуковое сопровождение действий агента; null — legacy, трактуется как включено. */
    val soundEnabled: Boolean? = null,
) {
    companion object {
        fun default(mode: AssistMode): ModeConfig = when (mode) {
            AssistMode.CHAT -> ModeConfig(mode)
            AssistMode.ASSISTANT -> ModeConfig(mode, assistant = AssistantExtras())
            AssistMode.SHARE -> ModeConfig(mode, share = ShareExtras())
        }
    }

    /** Озвучка режима с учётом старых конфигов без поля [tts]. */
    fun effectiveTts(): TtsConfig = tts ?: TtsConfig.DEFAULT

    /** Голосовой ввод режима с учётом старых конфигов без поля [stt]. */
    fun effectiveStt(): SttConfig = stt ?: SttConfig.DEFAULT
    /** Звуковое сопровождение с учётом старых конфигов без поля [soundEnabled]: дефолт — включено. */
    fun effectiveSoundEnabled(): Boolean = soundEnabled ?: true
}

/** Возможности модели — из ответа провайдера или пробы, правятся вручную. */
@Serializable
enum class ModelCapability {
    VISION,
    AUDIO_IN,
    TOOLS,
    REASONING,
}

/** Модель из списка провайдера. Список моделей никогда не захардкожен — только запрос к провайдеру. */
@Serializable
data class ModelInfo(
    val id: String,
    val label: String? = null,
    val capabilities: Set<ModelCapability> = emptySet(),
)

/** Кэш списка моделей провайдера. */
@Serializable
data class ModelCatalog(
    val providerId: String,
    val updatedAtEpochMs: Long,
    val models: List<ModelInfo> = emptyList(),
)
