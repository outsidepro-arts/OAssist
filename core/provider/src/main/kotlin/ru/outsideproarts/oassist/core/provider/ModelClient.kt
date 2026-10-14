package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.flow.Flow
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient
import ru.outsideproarts.oassist.core.provider.openai.OpenAiResponsesClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.provider.opencode.OpenCodeRoutingClient

/**
 * Резолвленный набор секретов и заголовков для конкретного запроса.
 * Plaintext живёт в CharArray, после использования вызывается [zeroOut].
 */
class ProviderAuth(
    val bearerToken: CharArray? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun zeroOut() {
        bearerToken?.fill('\u0000')
    }
}

/** Доменное сообщение чата: роль + контент (текстовый канал). */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /** Для [ChatRole.TOOL]: id вызова, на который отвечаем. */
    val toolCallId: String? = null,
    /** Для [ChatRole.ASSISTANT]: вызовы инструментов этого хода. */
    val toolCalls: List<ToolCallRef>? = null,
    /** Для [ChatRole.USER]: голосовое приложение (PTT). Null — обычное текстовое. */
    val audio: AudioPart? = null,
    /** Образы к сообщению (только USER/TOOL): скриншоты, вложения Share. Сжатый JPEG, см. ImageCodec. */
    val images: List<ImagePart> = emptyList(),
)

/**
 * Аудио-приложение к user-ходу: WAV 16 кГц моно в base64 (см. `WavEncoder`).
 * Строкой, а не ByteArray — чтобы не ломать equals истории и не тащить
 * бинарник через слои. Тяжёлое (~3.8 МБ/мин base64): кап длины — в захвате.
 */
data class AudioPart(
    val wavBase64: String,
    val mimeType: String = "audio/wav",
    val durationSec: Int = 0,
)

/**
 * Изображение в user-/tool-сообщении: сжатый JPEG в base64 (см. ImageCodec).
 * Строкой, не ByteArray — цел equals истории и тестов (как у [AudioPart]).
 */
data class ImagePart(
    val imageBase64: String,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
)

/** Ссылка на вызов инструмента в истории (пара к tool-результатам). */
data class ToolCallRef(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** Результат выполнения инструмента (`role: "tool"` + `tool_call_id`). */
    TOOL,
}

/** Элемент потока ответа. */
sealed interface ChatChunk {
    /**
     * Кусок текста. [isToolComment] ставит только [ru.outsideproarts.oassist.core.agent.AgentLoop]:
     * текст, которым агент сопровождает вызовы инструментов («ищу контакт…»).
     * Клиенты провайдеров всегда эмитят `false`; дефолт держит все места сборки.
     */
    data class Content(val delta: String, val isToolComment: Boolean = false) : ChatChunk
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val index: Int = 0,
    ) : ChatChunk
    /**
     * Результат выполнения инструмента — отдельная сущность, а не текст ответа.
     * UI показывает его в свёрнутом полотне «Ход работы», в синтез не идёт.
     * [output] уже обрезан исполнителем; [isError] — неуспех (отклонено/упало).
     */
    data class ToolResult(
        val id: String,
        val name: String,
        val output: String,
        val isError: Boolean = false,
        /** Образы из результата тула (скриншоты) — для UI и для модели при vision. */
        val images: List<ImagePart> = emptyList(),
    ) : ChatChunk
    /** Агент просит подтвердить выполнение инструмента; стрим приостановлен до решения. */
    data class ApprovalNeeded(val request: ApprovalRequest) : ChatChunk
    /**
     * Конец стрима. [abrupt] — соединение закрылось без штатного терминатора
     * (`[DONE]`/`message_stop`/`finish_reason`): ответ мог быть усечён,
     * UI показывает пометку вместо «успешного» завершения.
     * [usage] — статистика round-trip'а (только если провайдер её прислал);
     * у [ru.outsideproarts.oassist.core.agent.AgentLoop] несёт сумму по всем
     * раундам хода. Промежуточные Finish клиентов usage не несут — иначе
     * аккумуляция задвоила бы счётчики.
     */
    data class Finish(
        val reason: String?,
        val abrupt: Boolean = false,
        val usage: ProviderUsage? = null,
    ) : ChatChunk
}

/** Интерфейс клиента модели. */
interface ModelClient {
    /** Запрос списка моделей у провайдера. */
    suspend fun fetchModels(): List<ModelInfo>

    /**
     * Дешёвая проба возможностей модели: один короткий запрос с фиктивным
     * инструментом. HTTP 200 с принятым `tools` = [ModelCapability.TOOLS];
     * 400 (модель не принимает инструменты) = пусто; остальное — исключение.
     * VISION/AUDIO_IN/REASONING пробой не определяются — только вручную в UI.
     * Дефолт — пусто (фейки в тестах агента его не переопределяют).
     */
    suspend fun probeCapabilities(modelId: String): Set<ModelCapability> = emptySet()

    /** Стрим ответа на запрос чата. */
    fun chatCompletionStream(
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchemaDto> = emptyList(),
    ): Flow<ChatChunk>

    companion object {
        /**
         * Создаёт клиент по типу профиля: простой протокол — свой протокольный
         * клиент; [ProviderKind.OPENCODE] — агрегатор с роутингом по модели.
         * [overrides] — несохранённые значения из полей ввода (для проверки перед сохранением);
         * null-поля берутся из профиля/SecretStore как обычно.
         * [protocolOverride] — ручной выбор протокола модели (из каталога);
         * [modelsDev] — каталог models.dev для авто-резолва протокола/имён агрегаторов.
         */
        suspend fun create(
            profile: ProviderProfile,
            secretStore: ru.outsideproarts.oassist.core.data.secret.SecretStore,
            httpClient: io.ktor.client.HttpClient = ru.outsideproarts.oassist.core.net.HttpClientFactory.create(),
            overrides: ProviderAuth? = null,
            protocolOverride: suspend (modelId: String) -> ModelProtocol? = { null },
            modelsDev: ru.outsideproarts.oassist.core.provider.modelsdev.ModelsDevCatalog? = null,
        ): ModelClient {
            val auth = resolveProviderAuth(profile, secretStore, overrides)
            return when (profile.kind) {
                ProviderKind.OPENAI_COMPAT -> OpenAiCompatibleClient(
                    EndpointConfig(ModelProtocol.OPENAI_COMPLETIONS, profile.baseUrl, auth),
                    httpClient,
                )
                ProviderKind.ANTHROPIC -> AnthropicClient(
                    EndpointConfig(ModelProtocol.ANTHROPIC_MESSAGES, profile.baseUrl, auth),
                    httpClient,
                )
                ProviderKind.GEMINI -> GeminiClient(
                    EndpointConfig(ModelProtocol.GEMINI_GENERATE, profile.baseUrl, auth),
                    httpClient,
                )
                ProviderKind.OPENAI_RESPONSES -> OpenAiResponsesClient(
                    EndpointConfig(ModelProtocol.OPENAI_RESPONSES, profile.baseUrl, auth),
                    httpClient,
                )
                ProviderKind.OPENCODE -> OpenCodeRoutingClient(
                    profile.baseUrl,
                    auth,
                    httpClient,
                    protocolOverride,
                    modelsDev,
                )
            }
        }
    }
}
