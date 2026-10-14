package ru.outsideproarts.oassist.core.provider.opencode

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.EndpointConfig
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.UrlStyle
import ru.outsideproarts.oassist.core.provider.isProtocolUnsupported
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient
import ru.outsideproarts.oassist.core.provider.modelsdev.ModelsDevCatalog
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient
import ru.outsideproarts.oassist.core.provider.openai.OpenAiResponsesClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Агрегатор OpenCode (Zen и Go): один baseUrl + ключ, а модели списка
 * обслуживаются разными проводными протоколами (`/chat/completions`,
 * `/responses`, `/messages`, Gemini-пути).
 *
 * Цепочка резолва протокола модели (см. [ProtocolResolution]):
 * ручной override [protocolOverride] → `provider.npm` из [modelsDev] →
 * эвристика по id → chat/completions.
 *
 * Контракт baseUrl: уже с `/v1` (`https://opencode.ai/zen/v1`,
 * `https://opencode.ai/zen/go/v1`) — пути к протоколам дописываются как есть.
 * Список моделей — OpenAI-совместимый `{base}/models` на все протоколы.
 */
class OpenCodeRoutingClient internal constructor(
    private val baseUrl: String,
    private val auth: ProviderAuth,
    private val http: HttpClient,
    private val protocolOverride: suspend (modelId: String) -> ModelProtocol?,
    private val modelsDev: ModelsDevCatalog?,
) : ModelClient {

    private val requestHeaders = OpenCodeRequestHeaders()

    private val clients: Map<ModelProtocol, ModelClient> =
        ModelProtocol.entries.associateWith { protocol ->
            protocolClient(protocol, endpointFor(protocol), http)
        }

    /**
     * Выученные маршруты моделей: Zen явно сигналит `ModelProtocolUnsupported`
     * при неверном протоколе — фолбэк запоминает рабочий путь на жизнь клиента
     * (сессию). В каталог не пишется: `ModelInfo.protocol` — только ручной override.
     */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, ModelProtocol>()

    private fun endpointFor(protocol: ModelProtocol): EndpointConfig = EndpointConfig(
        protocol = protocol,
        baseUrl = baseUrl,
        auth = auth,
        urlStyle = UrlStyle.FLAT,
        requestHeaders = requestHeaders,
    )

    private suspend fun resolveProtocol(modelId: String): ModelProtocol = ProtocolResolution.resolve(
        modelId = modelId,
        override = protocolOverride(modelId),
        catalogNpm = modelsDev?.npmFor(catalogKey(baseUrl), modelId),
    )

    /** Основной протокол + до [MAX_ATTEMPTS]-х запасных при «не тот протокол». */
    private suspend fun protocolsFor(modelId: String): List<ModelProtocol> {
        val primary = learned[modelId] ?: resolveProtocol(modelId)
        return listOf(primary) + FALLBACK_ORDER.filter { it != primary }.take(MAX_ATTEMPTS - 1)
    }

    override suspend fun fetchModels(): List<ModelInfo> {
        val names = modelsDev?.namesFor(catalogKey(baseUrl)).orEmpty()
        return clients.getValue(ModelProtocol.OPENAI_COMPLETIONS).fetchModels().map { model ->
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
    ): Flow<ChatChunk> = flow {
        var lastError: Throwable? = null
        for (protocol in protocolsFor(modelId)) {
            var emittedAny = false
            try {
                clients.getValue(protocol).chatCompletionStream(modelId, messages, tools).collect { chunk ->
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

/**
 * Ключ среза models.dev по baseUrl агрегатора: путь с сегментом `go`
 * (`.../zen/go/v1`, `.../inference/go/v1`) — `opencode-go`, иначе `opencode`.
 */
internal fun catalogKey(baseUrl: String): String {
    val path = baseUrl.substringAfter("://", baseUrl).substringAfter('/', "")
    return if (path.split('/').any { it.equals("go", ignoreCase = true) }) "opencode-go" else "opencode"
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

/** Порядок перепробовки протоколов при `ModelProtocolUnsupported`. */
private val FALLBACK_ORDER = listOf(
    ModelProtocol.OPENAI_COMPLETIONS,
    ModelProtocol.ANTHROPIC_MESSAGES,
    ModelProtocol.OPENAI_RESPONSES,
    ModelProtocol.GEMINI_GENERATE,
)

/** Всего попыток на модель (основной протокол + запасные). */
private const val MAX_ATTEMPTS = 3
