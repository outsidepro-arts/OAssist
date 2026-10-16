package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.flow.Flow
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
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
