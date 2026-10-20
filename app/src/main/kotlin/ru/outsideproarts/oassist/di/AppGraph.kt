package ru.outsideproarts.oassist.di

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.R
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
        ru.outsideproarts.oassist.core.speech.SpeechOutput(appContext).also { it.init() }
    }

    /** Событийные звуки агента (ogg в core/speech res/raw); предзагрузка — в init ниже. */
    val agentSounds: ru.outsideproarts.oassist.core.speech.AgentSounds by lazy {
        ru.outsideproarts.oassist.core.speech.AgentSounds(appContext)
    }

    val narrator: ru.outsideproarts.oassist.core.speech.Narrator by lazy {
        ru.outsideproarts.oassist.core.speech.Narrator()
    }

    /** Процесс-скоупные итоги использования (in-memory): сумма по ходам всех режимов. */
    val usageTotals: ru.outsideproarts.oassist.core.model.UsageTotals by lazy {
        ru.outsideproarts.oassist.core.model.UsageTotals()
    }

    /** Мост «цикл → нарратив»: вызовы тулов уходят в таймлайн и объявления. */
    val narratorEvents: ru.outsideproarts.oassist.core.agent.AgentEvents by lazy {
        object : ru.outsideproarts.oassist.core.agent.AgentEvents {
            override suspend fun onToolCall(toolName: String, argumentsJson: String, summary: String) {
                narrator.onToolCall(toolName, argumentsJson, summary)
            }

            override suspend fun onToolResult(toolName: String, output: String, success: Boolean) {
                narrator.onToolResult(toolName, output, success)
            }

            override suspend fun onTurnUsage(usage: ru.outsideproarts.oassist.core.model.ProviderUsage) {
                usageTotals.record(usage)
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

    /** Каталог models.dev: маршрутизация протокола моделей агрегаторов (OpenCode). */
    val modelsDevCatalog by lazy {
        ru.outsideproarts.oassist.core.provider.modelsdev.ModelsDevCatalog(
            httpClient,
            ru.outsideproarts.oassist.core.provider.modelsdev.FileModelsDevCache(
                java.io.File(deviceContext.filesDir, "models-dev"),
            ),
        )
    }

    /** Живое состояние сети для пре-чеков и авто-повторов (см. NetworkHandle). */
    private val networkMonitor = ru.outsideproarts.oassist.core.net.NetworkMonitor(appContext)

    /** Экран блокировки: модель и диалог получают пометку, пользователю — «разблокируйте». */
    val keyguardLockState: ru.outsideproarts.oassist.permission.KeyguardLockState by lazy {
        ru.outsideproarts.oassist.permission.KeyguardLockState(appContext)
    }

    /** Запрос runtime-разрешений тулов в момент действия (ConsentActivity). */
    val permissionRequester: ru.outsideproarts.oassist.core.agent.PermissionRequester by lazy {
        ru.outsideproarts.oassist.permission.AppPermissionRequester(appContext)
    }

    /**
     * Общая на процесс политика подтверждений: запомненные решения групп
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
            overlayControl = { ru.outsideproarts.oassist.core.agent.OverlayControl.get() },
            accessGranted = {
                ru.outsideproarts.oassist.a11y.OAssistAccessibilityService.isEnabled(appContext)
            },
            requestReconnect = { reassertAccessibilityService() },
        )
    }

    /**
     * Пере-заявка нашего сервиса доступности: тогл компоненты в secure-настройках
     * (единственный рычаг без участия пользователя — система перестаёт держать
     * привязку в фоне). Выключение обязательно возвращается — NonCancellable.
     */
    private suspend fun reassertAccessibilityService() {
        val priv = privilegeExecutor()
        val key = ru.outsideproarts.oassist.core.agent.ScreenCurtain.ENABLED_ACCESSIBILITY_SERVICES
        val current = priv.getSetting("secure", key)
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val component = android.content.ComponentName(
            appContext,
            ru.outsideproarts.oassist.a11y.OAssistAccessibilityService::class.java,
        ).flattenToString()
        val (without, removed) =
            ru.outsideproarts.oassist.core.agent.removeServiceComponent(current, component)
        if (!removed) return
        if (priv.putSetting("secure", key, without).isFailure) return
        kotlinx.coroutines.delay(REASSERT_SETTLE_MS)
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { priv.putSetting("secure", key, current) }
        }
    }

    /** Операции над уведомлениями через пассивный NotificationListenerService. */
    val notifyActions: ru.outsideproarts.oassist.core.agent.NotifyActions by lazy {
        ru.outsideproarts.oassist.core.agent.NotifyActions(
            serviceProvider = {
                ru.outsideproarts.oassist.notify.OAssistNotificationListener.instance
            },
            accessGranted = {
                ru.outsideproarts.oassist.notify.OAssistNotificationListener.isEnabled(appContext)
            },
            requestRebind = {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(
                        appContext,
                        ru.outsideproarts.oassist.notify.OAssistNotificationListener::class.java,
                    ),
                )
            },
        )
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
    val searchActions: ru.outsideproarts.oassist.core.agent.SearchActions by lazy {
        ru.outsideproarts.oassist.core.agent.SearchActions(
            webContentActions,
            object : ru.outsideproarts.oassist.core.agent.SearchConfigSource {
                override suspend fun engine() = data.searchConfig.snapshot()
            },
        )
    }

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
        ModelClient.create(
            profile,
            data.secretStore,
            httpClient,
            protocolOverride = { modelId ->
                data.providers.observeModelCatalog(profile.id).first()
                    ?.models?.firstOrNull { it.id == modelId }?.protocol
            },
            modelsDev = modelsDevCatalog,
        )

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
                searchActions,
                resolveMcpTools(),
            )
            val modeModelId = data.providers.modeConfig(mode).modelId
            val visionEnabled = data.providers.observeModelCatalog(profile.id).first()
                ?.models?.find { it.id == modeModelId }
                ?.capabilities
                ?.contains(ru.outsideproarts.oassist.core.model.ModelCapability.VISION) == true
            return ru.outsideproarts.oassist.core.agent.AgentLoop(
                client,
                tools,
                approvalPolicy = approvalPolicy,
                events = narratorEvents,
                visionEnabled = visionEnabled,
                maxToolRounds = prefs.maxToolRounds,
                sessionScope = mode.name,
                permissionRequester = permissionRequester,
                screenLocked = { keyguardLockState.isLocked() },
            )
        }

        override fun clearSessionApprovals(mode: ru.outsideproarts.oassist.core.model.AssistMode) {
            approvalPolicy.clearSession(mode.name)
        }

        override fun usageTotals(): kotlinx.coroutines.flow.StateFlow<ru.outsideproarts.oassist.core.model.UsageTotalsState> =
            usageTotals.state

        override fun resetUsageTotals() {
            usageTotals.reset()
        }

        override suspend fun systemPromptFor(
            mode: ru.outsideproarts.oassist.core.model.AssistMode,
            screenContext: String?,
            sharedContent: String?,
        ): String = buildSystemPrompt(mode, screenContext, sharedContent)
    }

    /**
     * Системный промпт режима: сохранённый шаблон initialPrompt как есть,
     * память, включённые навыки, контекст экрана/шаринга. Фолбэка
     * «пусто — промпт по умолчанию» нет: поле всегда хранит текст
     * (legacy-null трактуется как пусто и материализуется в дефолт
     * при первом открытии/сохранении экрана режима).
     */
    suspend fun buildSystemPrompt(
        mode: ru.outsideproarts.oassist.core.model.AssistMode,
        screenContext: String? = null,
        sharedContent: String? = null,
    ): String {
        val config = data.providers.modeConfig(mode)
        val language = promptLanguage()
        val template = config.initialPrompt.orEmpty()
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

    /**
     * Дефолтный промпт режима из assets/prompts в языке приложения.
     * Источник истины для предзаполнения поля и кнопки «Восстановить
     * по умолчанию» в экране режима.
     */
    suspend fun defaultPromptText(
        mode: ru.outsideproarts.oassist.core.model.AssistMode,
    ): String = defaultPrompt(mode, promptLanguage())

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
                            serverId = config.id,
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
        ru.outsideproarts.oassist.core.data.chat.ChatHandle.register(data.chats)
        networkMonitor.start()
        ru.outsideproarts.oassist.core.net.NetworkHandle.register(
            object : ru.outsideproarts.oassist.core.net.NetworkHandle {
                override val isOnline: kotlinx.coroutines.flow.StateFlow<Boolean> =
                    networkMonitor.isOnline
            },
        )
        ru.outsideproarts.oassist.core.speech.SpeechHandle.register(object : ru.outsideproarts.oassist.core.speech.SpeechHandle {
            override fun speak(sentence: String) = speechOutput.speak(sentence)
            override suspend fun speakWhenReady(text: String): Boolean =
                speechOutput.speakWhenReady(text)
            override fun stop() = speechOutput.stop()
            override val speaking: kotlinx.coroutines.flow.StateFlow<Boolean>
                get() = speechOutput.speaking
            override val announcer: ru.outsideproarts.oassist.core.a11y.FlowAnnouncer
                get() = this@AppGraph.announcer
            override fun applyTts(config: ru.outsideproarts.oassist.core.model.TtsConfig) = applyModeTts(config)
            override fun setNarrativeEnabled(enabled: Boolean) {
                narrator.enabled = enabled
            }
            override fun setScreenReaderAnchor(view: android.view.View?) {
                speechOutput.setScreenReaderView(view)
            }
            override fun announceToScreenReader(text: String) {
                speechOutput.announceToScreenReader(text)
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
        startNarratorCollector()
        ru.outsideproarts.oassist.core.speech.SoundHandle.register(object : ru.outsideproarts.oassist.core.speech.SoundHandle {
            override fun play(sound: ru.outsideproarts.oassist.core.speech.AgentSound) =
                agentSounds.play(sound)
            override suspend fun playOverlayStartAndAwait() =
                agentSounds.playOverlayStartAndAwait()
        })
        persistScope.launch { runCatching { agentSounds.init() } }
    }

    /**
     * Процесс-скоуп коллектор нарратива: форматирует события и отправляет
     * через `announceToScreenReader` (всегда через экранный чтец, независимо
     * от канала ответа). Живёт, пока жив процесс — работает в оверлее, чате,
     * шаринге, даже если MainActivity мертва.
     */
    private fun startNarratorCollector() {
        persistScope.launch {
            narrator.events.collect { event ->
                val text = when (event) {
                    is ru.outsideproarts.oassist.core.speech.Narrator.NarrativeEvent.ToolCall -> {
                        val name = displayToolName(appContext, event.toolName)
                        if (event.arguments.isNotBlank()) {
                            appContext.getString(R.string.narrator_tool_call, name, event.arguments)
                        } else {
                            appContext.getString(R.string.narrator_tool_call_short, name)
                        }
                    }
                    is ru.outsideproarts.oassist.core.speech.Narrator.NarrativeEvent.ToolResult -> {
                        val name = displayToolName(appContext, event.toolName)
                        if (event.success) {
                            appContext.getString(R.string.narrator_tool_done, name)
                        } else {
                            val brief = event.output.take(120).trim()
                            if (brief.isNotBlank()) {
                                appContext.getString(R.string.narrator_tool_error, name, brief)
                            } else {
                                appContext.getString(R.string.narrator_tool_error_short, name)
                            }
                        }
                    }
                    is ru.outsideproarts.oassist.core.speech.Narrator.NarrativeEvent.Thought ->
                        event.text.take(500)
                }
                if (text.isNotBlank()) speechOutput.announceToScreenReader(text)
            }
        }
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

    /**
     * Человекочитаемое имя тула для нарратора (TalkBack не должен слышать сырой
     * идентификатор «web_fetch» / «device_state»). Ищем строковый ресурс
     * `tool_<name>_title` в пакете; MCP-тулы (`mcp_<id>__<tool>`) оставляем как есть.
     */
    private fun displayToolName(context: android.content.Context, toolName: String): String {
        val resId = context.resources.getIdentifier(
            "tool_${toolName.replace("-", "_")}_title",
            "string",
            context.packageName,
        )
        return if (resId != 0) context.getString(resId) else toolName
    }

    companion object {
        private const val MCP_LIST_TIMEOUT_MS = 20_000L
        private const val REASSERT_SETTLE_MS = 400L

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
