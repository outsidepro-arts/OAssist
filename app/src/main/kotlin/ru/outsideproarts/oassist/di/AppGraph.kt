package ru.outsideproarts.oassist.di

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.first
import ru.outsideproarts.oassist.core.a11y.FlowAnnouncer
import ru.outsideproarts.oassist.core.data.DataContainer
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.net.HttpClientFactory
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.model.ProviderProfile

/**
 * Ручной DI. Одна точка сборки зависимостей, никакой кодогенерации.
 * Сервисы и оверлеи получают доступ через [AppGraph.get].
 */
class AppGraph private constructor(context: Context) {

    private val appContext = context.applicationContext
    val deviceContext: Context = appContext.createDeviceProtectedStorageContext()

    val data: DataContainer by lazy {
        DataContainer(credentialContext = appContext, deviceContext = deviceContext)
    }

    val announcer: FlowAnnouncer by lazy { FlowAnnouncer() }

    val speechOutput: ru.outsideproarts.oassist.core.speech.SpeechOutput by lazy {
        ru.outsideproarts.oassist.core.speech.SpeechOutput(appContext, announcer).also { it.init() }
    }

    val narrator: ru.outsideproarts.oassist.core.speech.Narrator by lazy {
        ru.outsideproarts.oassist.core.speech.Narrator()
    }

    /** Мост «цикл → нарратив»: вызовы тулов уходят в таймлайн и объявления. */
    val narratorEvents: ru.outsideproarts.oassist.core.agent.AgentEvents by lazy {
        object : ru.outsideproarts.oassist.core.agent.AgentEvents {
            override suspend fun onToolCall(toolName: String, argumentsJson: String) {
                narrator.onToolCall(toolName, argumentsJson)
            }

            override suspend fun onToolResult(toolName: String, output: String) {
                narrator.onToolResult(toolName, output)
            }
        }
    }

    /** Озвучка активного режима: UI применяет [ru.outsideproarts.oassist.core.model.TtsConfig] перед каждым стримом. */
    fun applyModeTts(config: ru.outsideproarts.oassist.core.model.TtsConfig) {
        speechOutput.applyConfig(config)
        narrator.enabled = config.narrative
    }

    val providers: ProviderRepository
        get() = data.providers

    val httpClient by lazy { HttpClientFactory.create() }

    /**
     * Общая на процесс политика подтверждений: записи «всегда разрешать»
     * переживают отдельный [ru.outsideproarts.oassist.core.agent.AgentLoop]
     * (тот создаётся заново на каждое сообщение) и перезапуск приложения
     * (персист в device-protected DataStore, M5.3).
     */
    val approvalPolicy: ru.outsideproarts.oassist.core.agent.ApprovalPolicy by lazy {
        ru.outsideproarts.oassist.core.agent.PersistentApprovalPolicy(data.approvals, persistScope)
    }

    /** Процесс-скоп для персистентных коллекторов (не привязан к экранам). */
    private val persistScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** Трекер фонарика (in-memory; после смерти процесса — честный unknown). */
    val torchTracker: ru.outsideproarts.oassist.core.agent.TorchTracker by lazy {
        ru.outsideproarts.oassist.core.agent.TorchTracker()
    }

    /** Операции над экраном через пассивный AccessibilityService + фолбэк input. */
    val uiActions: ru.outsideproarts.oassist.core.agent.UiActions by lazy {
        ru.outsideproarts.oassist.core.agent.UiActions(
            serviceProvider = {
                ru.outsideproarts.oassist.a11y.OAssistAccessibilityService.instance
            },
            privProvider = { privilegeExecutor() },
        )
    }

    /** Операции над уведомлениями через пассивный NotificationListenerService. */
    val notifyActions: ru.outsideproarts.oassist.core.agent.NotifyActions by lazy {
        ru.outsideproarts.oassist.core.agent.NotifyActions {
            ru.outsideproarts.oassist.notify.OAssistNotificationListener.instance
        }
    }

    /** Привилегии M5.1: Shizuku (UserService) > Root (su) > Noop (явный отказ). */
    val shizukuExecutor: ru.outsideproarts.oassist.core.device.ShizukuExecutor by lazy {
        ru.outsideproarts.oassist.core.device.ShizukuExecutor(appContext)
    }
    val rootExecutor: ru.outsideproarts.oassist.core.device.RootExecutor by lazy {
        ru.outsideproarts.oassist.core.device.RootExecutor()
    }
    val noopExecutor: ru.outsideproarts.oassist.core.device.NoopExecutor by lazy {
        ru.outsideproarts.oassist.core.device.NoopExecutor()
    }

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * Текущий исполнитель привилегий. Результат пробы root кэшируется на процесс
     * (запрос `su -c id` может показывать диалог Superuser — не дёргаем каждый раз).
     */
    suspend fun privilegeExecutor(): ru.outsideproarts.oassist.core.device.PrivExecutor {
        if (shizukuExecutor.isBinderAlive() && shizukuExecutor.isPermissionGranted()) {
            return shizukuExecutor
        }
        val root = cachedRootAvailable ?: rootExecutor.isAvailable().also { cachedRootAvailable = it }
        if (root) return rootExecutor
        return noopExecutor
    }

    /** Чтение веба: статика (Ktor) + дорендер (WebView). */
    val webContentActions: ru.outsideproarts.oassist.core.agent.WebContentActions by lazy {
        ru.outsideproarts.oassist.core.agent.WebContentActions(httpClient)
    }

    /** Долгие операции (гео-фикс, WebView-рендер) под foreground-сервисом specialUse. */
    val foregroundRunner: ru.outsideproarts.oassist.core.agent.ForegroundRunner by lazy {
        ru.outsideproarts.oassist.tasks.AppForegroundRunner(appContext)
    }
    val webRenderActions: ru.outsideproarts.oassist.core.agent.WebRenderActions by lazy {
        ru.outsideproarts.oassist.core.agent.WebRenderActions(appContext, foregroundRunner)
    }

    /** Захват микрофона: PTT/тап UI и голосовые тулы агента поверх одного слушателя. */
    val voiceListener: ru.outsideproarts.oassist.core.speech.VoiceListener by lazy {
        ru.outsideproarts.oassist.core.speech.VoiceListener(appContext)
    }

    /** Контроллер голосовых тулов (`voice_listen_start/stop`, всегда STT-режим). */
    val voiceCapture: ru.outsideproarts.oassist.core.speech.VoiceCaptureController by lazy {
        ru.outsideproarts.oassist.core.speech.AgentVoiceCapture(voiceListener)
    }

    suspend fun modelClientFor(profile: ProviderProfile): ModelClient =
        ModelClient.create(profile, data.secretStore, httpClient)

    private val agentFactory = object : ru.outsideproarts.oassist.core.agent.AgentFactory {
        override suspend fun modeConfig(mode: ru.outsideproarts.oassist.core.model.AssistMode) =
            data.providers.modeConfig(mode)

        override suspend fun providerForMode(mode: ru.outsideproarts.oassist.core.model.AssistMode): ru.outsideproarts.oassist.core.model.ProviderProfile? {
            val config = data.providers.modeConfig(mode)
            return data.providers.observeProviders().first().find { it.id == config.providerId }
        }

        override suspend fun agentFor(profile: ProviderProfile, mode: ru.outsideproarts.oassist.core.model.AssistMode): ru.outsideproarts.oassist.core.agent.AgentLoop {
            val client = modelClientFor(profile)
            val prefs = data.toolConfigs.snapshot()
            val tools = ru.outsideproarts.oassist.core.agent.ToolCatalog.createRegistry(
                ru.outsideproarts.oassist.core.agent.DeviceActions(appContext, torchTracker, foregroundRunner),
                uiActions,
                notifyActions,
                webContentActions,
                webRenderActions,
                voiceCapture,
                data.memory,
                data.skills,
                prefs,
                privilegeExecutor(),
                resolveMcpTools(),
            )
            return ru.outsideproarts.oassist.core.agent.AgentLoop(client, tools, approvalPolicy = approvalPolicy, events = narratorEvents)
        }

        override suspend fun systemPromptFor(
            mode: ru.outsideproarts.oassist.core.model.AssistMode,
            screenContext: String?,
            sharedContent: String?,
        ): String = buildSystemPrompt(mode, screenContext, sharedContent)
    }

    /**
     * Системный промпт режима: свой шаблон или дефолт из assets/prompts,
     * память, включённые навыки, контекст экрана/шаринга.
     */
    suspend fun buildSystemPrompt(
        mode: ru.outsideproarts.oassist.core.model.AssistMode,
        screenContext: String? = null,
        sharedContent: String? = null,
    ): String {
        val config = data.providers.modeConfig(mode)
        val language = promptLanguage()
        val template = config.initialPrompt?.takeIf { it.isNotBlank() }
            ?: defaultPrompt(mode, language)
        return ru.outsideproarts.oassist.core.agent.PromptRenderer.render(
            template,
            ru.outsideproarts.oassist.core.agent.PromptInputs(
                locale = if (language == "ru") "ru-RU" else "en-US",
                time = promptTime(),
                device = promptDevice(),
                memory = runCatching { data.memory.read() }.getOrElse { "" },
                skills = runCatching { data.skills.list() }.getOrElse { emptyList() },
                screenContext = screenContext,
                sharedContent = sharedContent,
            ),
        )
    }

    private suspend fun promptLanguage(): String {
        val appLang = runCatching { data.settings.language.first() }
            .getOrNull() ?: ru.outsideproarts.oassist.core.model.AppLanguage.DEFAULT
        return when (appLang) {
            ru.outsideproarts.oassist.core.model.AppLanguage.ENGLISH -> "en"
            ru.outsideproarts.oassist.core.model.AppLanguage.RUSSIAN -> "ru"
            ru.outsideproarts.oassist.core.model.AppLanguage.SYSTEM ->
                if (java.util.Locale.getDefault().language == "ru") "ru" else "en"
        }
    }

    private fun defaultPrompt(
        mode: ru.outsideproarts.oassist.core.model.AssistMode,
        language: String,
    ): String {
        val file = "prompts/$language/${mode.name.lowercase()}.md"
        return runCatching {
            appContext.assets.open(file).bufferedReader().use { it.readText() }
        }.getOrElse { "" }
    }

    private fun promptTime(): String = runCatching {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
    }.getOrElse { "" }

    private fun promptDevice(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE}"

    /**
     * Внешние MCP-инструменты: по каждому включённому серверу — `tools/list`.
     * Сервер с ошибкой/таймаутом молча пропускается (его тулы просто не
     * предлагаются модели), чтобы один упавший сервер не ронял весь агент.
     */
    private suspend fun resolveMcpTools(): List<ru.outsideproarts.oassist.core.agent.Tool> {
        val servers = runCatching { data.mcpServers.snapshot() }.getOrElse { return emptyList() }
        return servers.filter { it.enabled }.flatMap { config ->
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(MCP_LIST_TIMEOUT_MS) {
                    val headers = data.mcpServers.resolveHeaders(config)
                    val client = ru.outsideproarts.oassist.core.agent.mcp.McpClient(httpClient, config.url, headers)
                    client.listTools().map { info ->
                        ru.outsideproarts.oassist.core.agent.mcp.McpTool(
                            serverName = config.name,
                            toolName = info.name,
                            displayName = ru.outsideproarts.oassist.core.agent.mcp.McpTool.displayName(config.id, info.name),
                            description = info.description,
                            parameters = info.inputSchema,
                            client = client,
                        )
                    }
                }
            }.getOrNull().orEmpty()
        }
    }

    init {
        ru.outsideproarts.oassist.core.agent.AgentFactory.register(agentFactory)
        ru.outsideproarts.oassist.core.speech.SpeechHandle.register(object : ru.outsideproarts.oassist.core.speech.SpeechHandle {
            override fun speak(sentence: String) = speechOutput.speak(sentence)
            override fun stop() = speechOutput.stop()
            override fun applyTts(config: ru.outsideproarts.oassist.core.model.TtsConfig) = applyModeTts(config)
            override fun setNarrativeEnabled(enabled: Boolean) {
                narrator.enabled = enabled
            }
        })
        ru.outsideproarts.oassist.core.speech.VoiceHandle.register(object : ru.outsideproarts.oassist.core.speech.VoiceHandle {
            override fun captureState() = voiceListener.state
            override fun isSttAvailable(): Boolean = voiceListener.isSttAvailable()
            override fun startStt(language: String, listener: ru.outsideproarts.oassist.core.speech.SttListener) =
                voiceListener.startStt(language, listener)
            override fun stopStt() = voiceListener.stopStt()
            override fun cancelStt() = voiceListener.cancelStt()
            override fun startRaw(maxSec: Int) = voiceListener.startRaw(maxSec)
            override fun stopRaw() = voiceListener.stopRaw()
            override fun cancelRaw() = voiceListener.cancelRaw()
        })
        registerTorchCallback()
    }

    /**
     * Слушать переключения фонарика (в т.ч. шторкой — не только нашим тулом).
     * Колбэк на инлайн-экзекьюторе: только запись в потокобезопасный трекер.
     */
    private fun registerTorchCallback() {
        runCatching {
            val cameraManager = appContext.getSystemService(android.hardware.camera2.CameraManager::class.java)
                ?: return
            cameraManager.registerTorchCallback(
                { it.run() },
                object : android.hardware.camera2.CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchTracker.onTorchChanged(cameraId, enabled)
                    }
                },
            )
        }
    }

    fun close() {
        data.close()
    }

    companion object {
        private const val MCP_LIST_TIMEOUT_MS = 20_000L

        @Volatile
        // Контексты уровня приложения, а не Activity/Service: синглтон безопасен.
        @SuppressLint("StaticFieldLeak")
        private var instance: AppGraph? = null

        fun init(context: Context): AppGraph {
            return instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
        }

        fun get(): AppGraph = checkNotNull(instance) {
            "AppGraph не инициализирован — OAssistApp.onCreate должен вызвать AppGraph.init"
        }
    }
}
