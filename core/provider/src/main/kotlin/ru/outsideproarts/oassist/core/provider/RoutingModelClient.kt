package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelFetchScheme
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient
import ru.outsideproarts.oassist.core.provider.modelsdev.ModelsDevCatalog
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient
import ru.outsideproarts.oassist.core.provider.openai.OpenAiResponsesClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.provider.opencode.ProtocolResolution
import ru.outsideproarts.oassist.core.provider.opencode.catalogKey
import ru.outsideproarts.oassist.core.provider.opencode.OpenCodeRequestHeaders

/**
 * Универсальный клиент с роутингом проводного протокола модели — для любого
 * провайдера, а не только агрегаторов.
 *
 * Протокол модели:
 * - фиксированный [fixedProtocol] профиля — один протокол на все модели
 *   (ручной per-model override всё равно побеждает), без перепробовки;
 * - null — авто-резолв ([ProtocolResolution]): override → `provider.npm` из
 *   [modelsDev] → эвристика по id → chat/completions, с фолбэком протоколов
 *   по сигналу «не тот протокол» (см. [isProtocolUnsupported]).
 *
 * Список моделей — по режиму каталога [fetchScheme] профиля: `{base}/models`
 * либо ручной ввод ([ModelFetchScheme.NONE]).
 * Оформление запросов (заголовки агрегатора) задаётся шаблоном
 * [template] и расширяется per-protocol.
 */
class RoutingModelClient internal constructor(
    private val template: EndpointConfig,
    private val http: HttpClient,
    private val fixedProtocol: ModelProtocol?,
    private val fetchScheme: ModelFetchScheme,
    private val protocolOverride: suspend (modelId: String) -> ModelProtocol?,
    private val modelsDev: ModelsDevCatalog?,
    private val catalogKey: suspend (baseUrl: String) -> String?,
) : ModelClient {

    private val clients: Map<ModelProtocol, ModelClient> =
        ModelProtocol.entries.associateWith { protocol ->
            protocolClient(protocol, template.withProtocol(protocol), http)
        }

    /**
     * Выученные маршруты моделей: сигнал «не тот протокол» и фолбэк запоминают
     * рабочий путь на жизнь клиента (сессию). В каталог не пишется:
     * `ModelInfo.protocol` — только ручной override.
     */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, ModelProtocol>()

    private suspend fun resolveProtocol(modelId: String): ModelProtocol {
        val override = protocolOverride(modelId)
        fixedProtocol?.let { return override ?: it }
        val key = catalogKey(template.baseUrl)
        return ProtocolResolution.resolve(
            modelId = modelId,
            override = override,
            catalogNpm = key?.let { modelsDev?.npmFor(it, modelId) },
        )
    }

    /**
     * Протоколы для попыток: основной + до [MAX_ATTEMPTS]-х запасных при
     * «не тот протокол». Фиксированный протокол профиля — одна попытка:
     * перепробовка противоречила бы явному выбору пользователя.
     */
    private suspend fun protocolsFor(modelId: String): List<ModelProtocol> {
        if (fixedProtocol != null) return listOf(resolveProtocol(modelId))
        val primary = learned[modelId] ?: resolveProtocol(modelId)
        return listOf(primary) + FALLBACK_ORDER.filter { it != primary }.take(MAX_ATTEMPTS - 1)
    }

    override suspend fun fetchModels(): List<ModelInfo> {
        if (fetchScheme == ModelFetchScheme.NONE) return emptyList()
        // Auth листинга следует фиксированному протоколу (нативная схема),
        // иначе — OpenAI-совместимый (Bearer), как у агрегаторов.
        val endpoint = template.withProtocol(fixedProtocol ?: ModelProtocol.OPENAI_COMPLETIONS)
        val models = fetchModelCatalog(endpoint, http)
        val key = catalogKey(template.baseUrl)
        val names = if (key != null) modelsDev?.namesFor(key).orEmpty() else emptyMap()
        return models.map { model ->
            if (model.label == null && names[model.id] != null) model.copy(label = names[model.id]) else model
        }
    }

    override suspend fun probeCapabilities(modelId: String): Set<ModelCapability> {
        var lastError: Throwable? = null
        for (protocol in protocolsFor(modelId)) {
            try {
                val result = clients.getValue(protocol).probeCapabilities(modelId)
                learned[modelId] = protocol
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isProtocolUnsupported(e)) throw e
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no protocols for $modelId")
    }

    override fun chatCompletionStream(
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchemaDto>,
        reasoningEnabled: Boolean,
    ): Flow<ChatChunk> = flow {
        var lastError: Throwable? = null
        for (protocol in protocolsFor(modelId)) {
            var emittedAny = false
            try {
                clients.getValue(protocol)
                    .chatCompletionStream(modelId, messages, tools, reasoningEnabled)
                    .collect { chunk ->
                        emittedAny = true
                        emit(chunk)
                    }
                learned[modelId] = protocol
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Повтор — только до первого чанка и только по явному сигналу
                // «не тот протокол»: после начала ответа перепробовка молча удвоила бы ход.
                if (emittedAny || !isProtocolUnsupported(e)) throw e
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no protocols for $modelId")
    }
}

/** Протокол → протокольный клиент уровня 1. */
internal fun protocolClient(
    protocol: ModelProtocol,
    endpoint: EndpointConfig,
    http: HttpClient,
): ModelClient = when (protocol) {
    ModelProtocol.OPENAI_COMPLETIONS -> OpenAiCompatibleClient(endpoint, http)
    ModelProtocol.OPENAI_RESPONSES -> OpenAiResponsesClient(endpoint, http)
    ModelProtocol.ANTHROPIC_MESSAGES -> AnthropicClient(endpoint, http)
    ModelProtocol.GEMINI_GENERATE -> GeminiClient(endpoint, http)
}

/** Шаблон запросов профиля для нового протокола (та же схема auth/оформления). */
internal fun EndpointConfig.withProtocol(protocol: ModelProtocol): EndpointConfig = EndpointConfig(
    protocol = protocol,
    baseUrl = baseUrl,
    auth = auth,
    authScheme = authScheme,
    apiKeyHeaderName = apiKeyHeaderName,
    requestHeaders = requestHeaders,
    extraBody = extraBody,
    catalogPath = catalogPath,
)

/** Декорации запросов пресета провайдера (заголовки агрегатора и т.п.). */
internal fun ProviderPreset.requestHeaders(): RequestHeaders = when (this) {
    ProviderPreset.OPENCODE -> OpenCodeRequestHeaders()
    else -> RequestHeaders.NONE
}

/**
 * Ключ среза models.dev пресета провайдера; null — каталог не используется
 * (произвольные хосты CUSTOM пока не сопоставляются с записями models.dev).
 */
internal fun ProviderPreset.catalogKeyResolver(): suspend (String) -> String? = when (this) {
    ProviderPreset.OPENCODE -> { { catalogKey(it) } }
    else -> { { null } }
}

/** Порядок перепробовки протоколов при «не тот протокол». */
private val FALLBACK_ORDER = listOf(
    ModelProtocol.OPENAI_COMPLETIONS,
    ModelProtocol.ANTHROPIC_MESSAGES,
    ModelProtocol.OPENAI_RESPONSES,
    ModelProtocol.GEMINI_GENERATE,
)

/** Всего попыток на модель (основной протокол + запасные). */
private const val MAX_ATTEMPTS = 3
