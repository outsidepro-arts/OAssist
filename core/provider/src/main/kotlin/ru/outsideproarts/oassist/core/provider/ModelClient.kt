package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.flow.Flow
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

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
    ) : ChatChunk
    /** Агент просит подтвердить выполнение инструмента; стрим приостановлен до решения. */
    data class ApprovalNeeded(val request: ApprovalRequest) : ChatChunk
    data class Finish(val reason: String?) : ChatChunk
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
         * Создаёт клиент по типу профиля.
         * [overrides] — несохранённые значения из полей ввода (для проверки перед сохранением);
         * null-поля берутся из профиля/SecretStore как обычно.
         */
        suspend fun create(
            profile: ProviderProfile,
            secretStore: ru.outsideproarts.oassist.core.data.secret.SecretStore,
            httpClient: io.ktor.client.HttpClient = ru.outsideproarts.oassist.core.net.HttpClientFactory.create(),
            overrides: ProviderAuth? = null,
        ): ModelClient = when (profile.kind) {
            ProviderKind.OPENAI_COMPAT, ProviderKind.OPENCODE_ZEN ->
                OpenAiCompatibleClient.fromProfile(profile, secretStore, httpClient, overrides)
            ProviderKind.ANTHROPIC ->
                AnthropicClient.fromProfile(profile, secretStore, httpClient, overrides)
            ProviderKind.GEMINI ->
                GeminiClient.fromProfile(profile, secretStore, httpClient, overrides)
        }
    }
}
