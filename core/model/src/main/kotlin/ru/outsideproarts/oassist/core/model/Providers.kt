package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Профиль LLM-провайдера. Секреты хранятся отдельно: в полях-ссылках — alias в `SecretStore`,
 * сами значения никогда не попадают в модель/БД/логи.
 *
 * [preset] — готовый рецепт известного провайдера ([ProviderPreset.OpenAI] и т.п.)
 * либо «Своя настройка» ([ProviderPreset.CUSTOM]) с открытыми полями; у пресетов
 * Base URL/протокол/каталог/авторизация заполняются его дефолтами и UI их прячет,
 * но значения пишутся в профиль явно. Проводной протокол, каталог и авторизация:
 * [protocol] (null — определять по модели: override → models.dev → эвристика),
 * [fetchScheme], [authScheme].
 *
 * [baseUrl] — корень API вместе с версией, как в документации провайдера
 * (`https://api.openai.com/v1`, `https://api.anthropic.com/v1`,
 * `https://generativelanguage.googleapis.com/v1beta`): все пути (диалоги и
 * каталог `{base}/[catalogPath]`) дописываются к нему как есть, без вставки версии.
 */
@Serializable(with = ProviderProfileSerializer::class)
data class ProviderProfile(
    val id: String,
    val name: String,
    val preset: ProviderPreset,
    val baseUrl: String,
    /** Проводной протокол профиля; null — определять по модели. */
    val protocol: ModelProtocol? = null,
    val fetchScheme: ModelFetchScheme = ModelFetchScheme.MODELS,
    val authScheme: AuthScheme = AuthScheme.NATIVE,
    /** Имя заголовка для [AuthScheme.API_KEY_HEADER]. */
    val apiKeyHeaderName: String? = null,
    /** Alias логина Basic в SecretStore ([AuthScheme.BASIC]). */
    val basicUserRef: String? = null,
    /** Alias пароля Basic в SecretStore ([AuthScheme.BASIC]). */
    val basicPasswordRef: String? = null,
    /** Alias API-ключа в SecretStore; null — ключ не задан. */
    val apiKeyRef: String? = null,
    /** Имя заголовка → alias его значения в SecretStore. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Путь каталога моделей; null — стандартный `models` (итог: `{base}/models`). */
    val catalogPath: String? = null,
    /**
     * Дополнительные параметры тела chat-запросов: JSON-объект текстом, ключи
     * мержатся поверх сериализованного тела. Проверяется при сохранении,
     * разбор — через [parseExtraBody].
     */
    val extraBody: String? = null,
    val enabled: Boolean = true,
)

/**
 * Проверка [ProviderProfile.extraBody]: пустое допустимо, иначе — валидный
 * JSON-объект (для UI-валидации при сохранении; сам разбор тела — в слое
 * провайдера).
 */
fun isValidExtraBody(extraBody: String?): Boolean {
    if (extraBody.isNullOrBlank()) return true
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(extraBody) is JsonObject
    }.getOrDefault(false)
}

/** Проводной формат [ProviderProfile] (новый JSON без легаси-полей). */
@Serializable
internal data class ProviderProfileWire(
    val id: String,
    val name: String,
    val preset: ProviderPreset,
    val baseUrl: String,
    val protocol: ModelProtocol? = null,
    val fetchScheme: ModelFetchScheme = ModelFetchScheme.MODELS,
    val authScheme: AuthScheme = AuthScheme.NATIVE,
    val apiKeyHeaderName: String? = null,
    val basicUserRef: String? = null,
    val basicPasswordRef: String? = null,
    val apiKeyRef: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val catalogPath: String? = null,
    val extraBody: String? = null,
    val enabled: Boolean = true,
)

private fun ProviderProfile.toWire(): ProviderProfileWire = ProviderProfileWire(
    id = id,
    name = name,
    preset = preset,
    baseUrl = baseUrl,
    protocol = protocol,
    fetchScheme = fetchScheme,
    authScheme = authScheme,
    apiKeyHeaderName = apiKeyHeaderName,
    basicUserRef = basicUserRef,
    basicPasswordRef = basicPasswordRef,
    apiKeyRef = apiKeyRef,
    extraHeaders = extraHeaders,
    catalogPath = catalogPath,
    extraBody = extraBody,
    enabled = enabled,
)

private fun ProviderProfileWire.fromWire(): ProviderProfile = ProviderProfile(
    id = id,
    name = name,
    preset = preset,
    baseUrl = baseUrl,
    protocol = protocol,
    fetchScheme = fetchScheme,
    authScheme = authScheme,
    apiKeyHeaderName = apiKeyHeaderName,
    basicUserRef = basicUserRef,
    basicPasswordRef = basicPasswordRef,
    apiKeyRef = apiKeyRef,
    extraHeaders = extraHeaders,
    catalogPath = catalogPath,
    extraBody = extraBody,
    enabled = enabled,
)

/**
 * Сериализация [ProviderProfile] с миграцией легаси-формата одним шагом:
 * старый `kind` разворачивается в пресет ([ProviderPreset]) при совпадении хоста
 * и стандартного пути либо в «Свою настройку» ([ProviderPreset.CUSTOM]) с фиксом
 * протокола и материализацией версии в `baseUrl` (`/v1`, `/v1beta`) — URL
 * запросов существующих профилей не меняются. `OPENCODE`/`OPENCODE_ZEN` (единый
 * клиент Zen/Go и старейший `opencode serve`) → пресет [ProviderPreset.OPENCODE]
 * при стандартном эндпоинте (Zen / Zen Go), иначе — CUSTOM с per-model резолвом
 * протокола. Легаси `basicAuthUserRef`/`basicAuthPasswordRef` игнорируются
 * осознанно (поля не выходили за пределы ранних тестов, Basic-секреты вычищены).
 */
object ProviderProfileSerializer : KSerializer<ProviderProfile> {
    private val wireSerializer = ProviderProfileWire.serializer()

    override val descriptor: SerialDescriptor = wireSerializer.descriptor

    override fun serialize(encoder: Encoder, value: ProviderProfile) {
        encoder.encodeSerializableValue(wireSerializer, value.toWire())
    }

    override fun deserialize(decoder: Decoder): ProviderProfile {
        val json = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(wireSerializer).fromWire()
        val obj = json.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("ProviderProfile: expected object")
        return decodeObject(obj)
    }

    private fun decodeObject(obj: JsonObject): ProviderProfile {
        val known = obj.filterKeys { it in WIRE_KEYS }
        val decoded = when {
            "preset" in obj -> known
            else -> {
                val raw = obj["kind"]?.jsonPrimitive?.contentOrNull
                    ?: throw SerializationException("ProviderProfile: neither preset nor kind")
                val legacyBase = known["baseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val mapping = migrateLegacy(raw, legacyBase)
                val derived = buildMap {
                    put("preset", JsonPrimitive(mapping.preset.name))
                    mapping.protocol?.let { put("protocol", JsonPrimitive(it.name)) }
                    put("fetchScheme", JsonPrimitive(ModelFetchScheme.MODELS.name))
                    put("authScheme", JsonPrimitive(AuthScheme.NATIVE.name))
                }.filterKeys { it !in known }
                val base = "baseUrl" to JsonPrimitive(
                    mapping.baseUrl ?: materializeVersion(legacyBase, mapping.versionSuffix),
                )
                known + derived + base
            }
        }
        return Json.decodeFromJsonElement(wireSerializer, JsonObject(decoded)).fromWire()
    }

    /**
     * Легаси `kind` → пресет и настройки одним шагом (auth всегда NATIVE —
     * прежняя схема). Пресет назначается только при совпадении хоста и
     * стандартного пути — URL запросов при этом не меняются. Исключение:
     * host-only base у OpenAI-пресета (раньше давал нерабочие пути вида
     * `{host}/chat/completions`) — такой конфиг чинится в пресет.
     */
    private fun migrateLegacy(raw: String, baseUrl: String): LegacyMapping {
        val protocol = when (raw) {
            "OPENAI_COMPAT" -> ModelProtocol.OPENAI_COMPLETIONS
            "OPENAI_RESPONSES" -> ModelProtocol.OPENAI_RESPONSES
            "ANTHROPIC" -> ModelProtocol.ANTHROPIC_MESSAGES
            "GEMINI" -> ModelProtocol.GEMINI_GENERATE
            "OPENCODE", "OPENCODE_ZEN" -> null
            else -> throw SerializationException("Unknown ProviderProfile kind: $raw")
        }
        val versionSuffix = when (raw) {
            "ANTHROPIC" -> "/v1"
            "GEMINI" -> "/v1beta"
            else -> null
        }
        val trimmed = baseUrl.trimEnd('/')
        val presetBase = when {
            raw == "OPENCODE" || raw == "OPENCODE_ZEN" ->
                OpenCodeEndpoint.entries.firstOrNull { it.baseUrl == trimmed }?.baseUrl
            raw == "OPENAI_COMPAT" || raw == "OPENAI_RESPONSES" ->
                if (hostMatches(trimmed, "api.openai.com")) "https://api.openai.com/v1" else null
            raw == "ANTHROPIC" ->
                if (hostMatches(trimmed, "api.anthropic.com")) "https://api.anthropic.com/v1" else null
            else ->
                if (hostMatches(trimmed, "generativelanguage.googleapis.com", extraPaths = setOf("/v1beta"))) {
                    GoogleApiVersion.fromBaseUrl(trimmed).baseUrl
                } else null
        }
        val preset = when {
            presetBase == null -> ProviderPreset.CUSTOM
            raw == "ANTHROPIC" -> ProviderPreset.ANTHROPIC
            raw == "GEMINI" -> ProviderPreset.GOOGLE
            raw == "OPENCODE" || raw == "OPENCODE_ZEN" -> ProviderPreset.OPENCODE
            else -> ProviderPreset.OPENAI
        }
        return LegacyMapping(preset, protocol, presetBase, versionSuffix)
    }

    /** Хост совпал со стандартным адресом пресета; путь — пустой, `/v1` или из [extraPaths]. */
    private fun hostMatches(
        trimmed: String,
        host: String,
        extraPaths: Set<String> = emptySet(),
    ): Boolean {
        val rest = trimmed.removePrefix("https://").removePrefix("http://")
        val slash = rest.indexOf('/')
        val urlHost = if (slash == -1) rest else rest.substring(0, slash)
        val path = if (slash == -1) "" else rest.substring(slash)
        return urlHost.equals(host, ignoreCase = true) && path in setOf("", "/v1") + extraPaths
    }

    /**
     * Материализация версии API в baseUrl при миграции в «Свою настройку»:
     * легаси-пути вставляли `/v1`/`/v1beta` сами (хвост в baseUrl терпился),
     * теперь версия — часть baseUrl. Условие дописывания совпадает со старой
     * терпимостью, поэтому URL существующих профилей не меняются.
     */
    private fun materializeVersion(baseUrl: String, suffix: String?): String {
        if (suffix == null) return baseUrl
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith(suffix)) trimmed else trimmed + suffix
    }

    private class LegacyMapping(
        val preset: ProviderPreset,
        val protocol: ModelProtocol?,
        /** Нормализованный base пресета; null — «Своя настройка». */
        val baseUrl: String?,
        /** Материализация версии в base для «Своей настройки». */
        val versionSuffix: String?,
    )

    private val WIRE_KEYS = setOf(
        "id", "name", "preset", "baseUrl", "protocol", "fetchScheme", "authScheme",
        "apiKeyHeaderName", "basicUserRef", "basicPasswordRef", "apiKeyRef",
        "extraHeaders", "catalogPath", "extraBody", "enabled",
    )
}

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
 * `voiceLocale` — язык выбора (BCP-47, `localeTag` голосов движка): для
 * выбранного голоса это его локаль, для голоса по умолчанию — язык синтеза;
 * null — язык не выбран.
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
    val voiceLocale: String? = null,
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
 * [bluetoothInput] — AUTO-маршрут записи: при подключённой BT-гарнитуре и
 * выданном BLUETOOTH_CONNECT писать с неё, иначе — микрофон.
 */
@Serializable
data class SttConfig(
    val engine: SttEngine = SttEngine.SYSTEM,
    val language: String = "ru-RU",
    val recognizerComponent: String? = null,
    val bluetoothInput: Boolean = true,
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
    /** Автосохранение новых диалогов в список чатов; null — legacy, трактуется как включено. */
    val autoSaveChats: Boolean? = null,
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

    /** Автосохранение диалогов по умолчанию для [autoSaveChats]: null — включено. */
    fun effectiveAutoSaveChats(): Boolean = autoSaveChats ?: true
}

/** Возможности модели — из ответа провайдера или пробы, правятся вручную. */
@Serializable
enum class ModelCapability {
    VISION,
    AUDIO_IN,
    TOOLS,
    REASONING,
}

/**
 * Модель из списка провайдера. Список моделей никогда не захардкожен — только запрос к провайдеру.
 *
 * [protocol] — **ручной override** проводного протокола модели (для агрегаторов);
 * null — авто-резолв (каталог моделей → эвристика по id → chat/completions).
 * Сознательно не «решённый» протокол: авто-резолв живой и не персистится.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val label: String? = null,
    val capabilities: Set<ModelCapability> = emptySet(),
    val protocol: ModelProtocol? = null,
)

/** Кэш списка моделей провайдера. */
@Serializable
data class ModelCatalog(
    val providerId: String,
    val updatedAtEpochMs: Long,
    val models: List<ModelInfo> = emptyList(),
)
