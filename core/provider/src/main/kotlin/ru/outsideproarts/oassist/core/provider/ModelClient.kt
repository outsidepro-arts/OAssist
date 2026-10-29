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
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Резолвленный набор секретов и заголовков для конкретного запроса.
 * Plaintext живёт в CharArray, после использования вызывается [zeroOut].
 * Размещение ключа/пароля по запросу задаёт схема авторизации
 * (см. [ru.outsideproarts.oassist.core.model.AuthScheme]).
 */
class ProviderAuth(
    /** Секрет API-ключа (Bearer/нативный key-заголовок/именованный заголовок). */
    val apiKey: CharArray? = null,
    /** Логин Basic ([ru.outsideproarts.oassist.core.model.AuthScheme.BASIC]). */
    val basicUser: String? = null,
    /** Пароль Basic ([ru.outsideproarts.oassist.core.model.AuthScheme.BASIC]). */
    val basicPassword: CharArray? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun zeroOut() {
        basicPassword?.fill(Char.MIN_VALUE)
        apiKey?.fill('\u0000')
    }
}

/** Элемент потока ответа. */
sealed interface ChatChunk {
    /**
     * Кусок текста. [isToolComment] ставит только [ru.outsideproarts.oassist.core.agent.AgentLoop]:
     * текст, которым агент сопровождает вызовы инструментов («ищу контакт…»).
     * Клиенты провайдеров всегда эмитят `false`; дефолт держит все места сборки.
     */
    data class Content(val delta: String, val isToolComment: Boolean = false, val isTerminalFarewell: Boolean = false) : ChatChunk
    /**
     * Кусок «мыслей» модели (DeepSeek `reasoning_content`, Gemini thought-части,
     * Anthropic `thinking_delta`, события reasoning у Responses). Эмитится клиентами
     * live (без буферизации раунда): UI показывает процесс размышления в полотне
     * «Ход работы». В текст ответа не попадает.
     *
     * Пустой [delta] с заполненными [signature]/[replayJson] — служебная
     * «приписка» к ходу: подпись Anthropic для replay thinking-блока или сырой
     * JSON reasoning-item'ов Responses. UI их игнорирует, [ru.outsideproarts.
     * oassist.core.agent.AgentLoop] подшивает к `ChatMessage.reasoning`.
     */
    data class Reasoning(
        val delta: String,
        val signature: String? = null,
        val replayJson: String? = null,
    ) : ChatChunk
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
        val closeSession: Boolean = false,
    ) : ChatChunk
}

/** Интерфейс клиента модели. */
interface ModelClient {
    /** Values used only for synchronous diagnostic redaction during this client call. */
    fun diagnosticSecrets(): Collection<String> = emptyList()
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

    /** Стрим ответа на запрос чата.
     *  [reasoningEnabled] — у модели включён флаг «Мышление» (REASONING capability):
     *  Anthropic шлёт `thinking` в запросе, Responses — `reasoning.summary`;
     *  остальные протоколы мыслят сами и флаг не используют. */
    fun chatCompletionStream(
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchemaDto> = emptyList(),
        reasoningEnabled: Boolean = false,
    ): Flow<ChatChunk>

    companion object {
        /**
         * Создаёт клиент профиля: роутинг проводного протокола для любого
         * провайдера — фиксированный протокол профиля либо per-model авто-резолв
         * с фолбэком (см. [RoutingModelClient]); схема фетча каталога и авторизация
         * — явные настройки профиля ([ProviderProfile]).
         * [overrides] — несохранённые значения из полей ввода (для проверки перед сохранением);
         * null-поля берутся из профиля/SecretStore как обычно.
         * [protocolOverride] — ручной выбор протокола модели (из каталога);
         * [modelsDev] — каталог models.dev для авто-резолва протокола/имён агрегаторов.
         */
        suspend fun create(
            diagnostics: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder,
            profile: ProviderProfile,
            secretStore: ru.outsideproarts.oassist.core.data.secret.SecretStore,
            httpClient: io.ktor.client.HttpClient = ru.outsideproarts.oassist.core.net.HttpClientFactory.create(diagnostics),
            overrides: ProviderAuth? = null,
            protocolOverride: suspend (modelId: String) -> ModelProtocol? = { null },
            modelsDev: ru.outsideproarts.oassist.core.provider.modelsdev.ModelsDevCatalog? = null,
        ): ModelClient {
            val auth = resolveProviderAuth(profile, secretStore, overrides)
            return RoutingModelClient(
                template = endpointTemplate(profile, auth, diagnostics),
                http = httpClient,
                fixedProtocol = profile.protocol,
                fetchScheme = profile.fetchScheme,
                protocolOverride = protocolOverride,
                modelsDev = modelsDev,
                catalogKey = profile.preset.catalogKeyResolver(),
                diagnostics = diagnostics,
            )
        }

        /** Шаблон точек подключения профиля: схема auth и оформление — из настроек. */
        private fun endpointTemplate(profile: ProviderProfile, auth: ProviderAuth, diagnostics: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder): EndpointConfig =
            EndpointConfig(
                diagnostics = diagnostics,
                protocol = profile.protocol ?: ModelProtocol.OPENAI_COMPLETIONS,
                baseUrl = profile.baseUrl,
                auth = auth,
                authScheme = profile.authScheme,
                apiKeyHeaderName = profile.apiKeyHeaderName,
                requestHeaders = profile.preset.requestHeaders(),
                extraBody = extraBodyJson(profile.extraBody),
                catalogPath = profile.catalogPath,
            )
    }
}
