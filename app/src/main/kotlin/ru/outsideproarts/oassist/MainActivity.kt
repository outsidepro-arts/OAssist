package ru.outsideproarts.oassist

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.AnnouncerHost
import ru.outsideproarts.oassist.core.a11y.FlowAnnouncer
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.ProviderValidationResult
import ru.outsideproarts.oassist.core.provider.ProviderValidator
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme
import ru.outsideproarts.oassist.di.AppGraph
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.settings.AssistantAccessScreen
import ru.outsideproarts.oassist.feature.settings.ModeConfigScreen
import ru.outsideproarts.oassist.feature.settings.ProviderEditScreen
import ru.outsideproarts.oassist.feature.settings.ProviderListScreen
import ru.outsideproarts.oassist.feature.settings.ProviderSecretsInput
import ru.outsideproarts.oassist.feature.settings.SettingsScreen
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get()

        setContent {
            OAssistTheme {
                val language by graph.data.settings.language
                    .collectAsStateWithLifecycle(initialValue = AppLanguage.DEFAULT)

                LaunchedEffect(language) {
                    applyLanguage(language)
                }

                val scope = rememberCoroutineScope()
                OAssistApp(
                    graph = graph,
                    announcer = graph.announcer,
                    language = language,
                    onLanguageChange = { lang ->
                        scope.launch { graph.data.settings.setLanguage(lang) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Якорь анонсов скринридера (аналог TeamTalk announcerObject): оверлей
        // при показе перепривязывает на своё окно, возврат сюда — через onResume.
        runCatching {
            AppGraph.get().speechOutput.setScreenReaderView(window.decorView)
        }
    }

    override fun onDestroy() {
        runCatching { AppGraph.get().speechOutput.setScreenReaderView(null) }
        super.onDestroy()
    }

    private fun applyLanguage(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.RUSSIAN -> LocaleListCompat.forLanguageTags("ru")
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

private sealed class TopDest(val route: String, val labelRes: Int) {
    data object Chat : TopDest("chat", R.string.nav_chat)
    data object Settings : TopDest("settings", R.string.nav_settings)

    companion object {
        val ALL = listOf(Chat, Settings)
    }
}

@Composable
private fun OAssistApp(
    graph: AppGraph,
    announcer: FlowAnnouncer,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val selectedState = stringResource(R.string.state_selected)
    val unselectedState = stringResource(R.string.state_unselected)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TopDest.ALL.forEach { dest ->
                    val selected = currentRoute == dest.route
                    val label = stringResource(dest.labelRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (dest) {
                                    TopDest.Chat -> Icons.AutoMirrored.Filled.Chat
                                    TopDest.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(label) },
                        modifier = Modifier
                            .oassistRole(Role.Tab)
                            .semantics {
                                stateDescription = if (selected) selectedState else unselectedState
                            },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDest.Chat.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopDest.Chat.route) {
                val modeConfigs by graph.providers.observeModeConfigs()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val chatConfig = modeConfigs.find { it.mode == AssistMode.CHAT }
                val context = LocalContext.current
                // Флаг AUDIO_IN — только ручной Switch capabilities модели.
                var chatModels by remember { mutableStateOf(emptyList<ru.outsideproarts.oassist.core.model.ModelInfo>()) }
                LaunchedEffect(chatConfig?.providerId) {
                    chatModels = chatConfig?.providerId?.let { id ->
                        graph.providers.observeModelCatalog(id).first()?.models
                    } ?: emptyList()
                }
                val chatAudioIn = chatModels.find { it.id == chatConfig?.modelId }
                    ?.capabilities?.contains(ru.outsideproarts.oassist.core.model.ModelCapability.AUDIO_IN) == true
                ChatScreen(
                    sendMessage = { mode, userMsg, history, audio, onTurnMessages ->
                        val config = graph.providers.observeModeConfigs().first()
                            .find { it.mode == mode }
                        val profile = graph.providers.observeProviders().first()
                            .find { it.id == config?.providerId }
                            ?: throw ru.outsideproarts.oassist.core.provider.ProviderError.NotConfigured(
                                ru.outsideproarts.oassist.core.provider.ProviderError.NotConfigured.Reason.PROVIDER_MISSING,
                            )
                        val modelId = config?.modelId?.takeIf { it.isNotBlank() }
                            ?: throw ru.outsideproarts.oassist.core.provider.ProviderError.NotConfigured(
                                ru.outsideproarts.oassist.core.provider.ProviderError.NotConfigured.Reason.MODEL_MISSING,
                            )
                        // Озвучка режима применяется перед каждым стримом.
                        graph.applyModeTts(config?.effectiveTts() ?: ru.outsideproarts.oassist.core.model.TtsConfig.DEFAULT)
                        val client = graph.modelClientFor(profile)
                        val prefs = graph.data.toolConfigs.snapshot()
                        val toolRegistry = ru.outsideproarts.oassist.core.agent.ToolCatalog.createRegistry(
                            ru.outsideproarts.oassist.core.agent.DeviceActions(
                                context.applicationContext,
                                graph.torchTracker,
                                graph.foregroundRunner,
                            ),
                            graph.uiActions,
                            graph.notifyActions,
                            graph.webContentActions,
                            graph.webRenderActions,
                            graph.voiceCapture,
                            graph.data.memory,
                            graph.data.skills,
                            prefs,
                            graph.privilegeExecutor(),
                            graph.searchActions,
                        )
                        val visionEnabled = config?.providerId
                            ?.let { graph.providers.observeModelCatalog(it).first()?.models }
                            .orEmpty()
                            .find { it.id == modelId }
                            ?.capabilities
                            ?.contains(ru.outsideproarts.oassist.core.model.ModelCapability.VISION) == true
                        val agent = ru.outsideproarts.oassist.core.agent.AgentLoop(
                            client,
                            toolRegistry,
                            approvalPolicy = graph.approvalPolicy,
                            events = graph.narratorEvents,
                            visionEnabled = visionEnabled,
                            sessionScope = mode.name,
                            permissionRequester = graph.permissionRequester,
                            screenLocked = { graph.keyguardLockState.isLocked() },
                        )
                        ru.outsideproarts.oassist.core.agent.AgentSession(
                            chunks = agent.stream(
                                modelId = modelId,
                                systemPrompt = graph.buildSystemPrompt(mode),
                                history = history,
                                userMessage = userMsg,
                                audio = audio,
                                onTurnMessages = onTurnMessages,
                            ),
                            agent = agent,
                        )
                    },
                    speakSentence = { graph.speechOutput.speak(it) },
                    stopSpeech = { graph.speechOutput.stop() },
                    speakToolCalls = chatConfig?.effectiveTts()?.speakToolCalls ?: true,
                    soundEnabled = chatConfig?.effectiveSoundEnabled() ?: true,
                    stt = chatConfig?.effectiveStt() ?: ru.outsideproarts.oassist.core.model.SttConfig.DEFAULT,
                    audioIn = chatAudioIn,
                    timeline = graph.narrator.timeline,
                    usageTotals = graph.usageTotals.state,
                )
            }
            composable("assistant_access") {
                val context = LocalContext.current
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                var accessVersion by remember { mutableStateOf(0) }
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) accessVersion++
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val a11yEnabled = remember(accessVersion) {
                    ru.outsideproarts.oassist.a11y.OAssistAccessibilityService.isEnabled(context)
                }
                val notifEnabled = remember(accessVersion) {
                    ru.outsideproarts.oassist.notify.OAssistNotificationListener.isEnabled(context)
                }
                var shizukuGranted by remember { mutableStateOf(false) }
                val rootAvailable = remember(accessVersion) {
                    ru.outsideproarts.oassist.core.device.RootExecutor.hasSuBinary()
                }
                androidx.compose.runtime.LaunchedEffect(accessVersion) {
                    shizukuGranted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { graph.shizukuExecutor.isPermissionGranted() }.getOrDefault(false)
                    }
                }
                val bgStartVisible = remember { ru.outsideproarts.oassist.core.agent.BgStartGuard.isMiui() }
                var bgStartAllowed by remember { mutableStateOf(true) }
                val bgStartCommand = remember {
                    ru.outsideproarts.oassist.core.agent.BgStartGuard.instruction(context.packageName)
                }
                androidx.compose.runtime.LaunchedEffect(accessVersion) {
                    bgStartAllowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        !ru.outsideproarts.oassist.core.agent.BgStartGuard.isOpRestricted(context)
                    }
                }
                AssistantAccessScreen(
                    isAccessibilityEnabled = a11yEnabled,
                    onOpenAccessibilitySettings = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    isNotificationEnabled = notifEnabled,
                    onOpenNotificationSettings = {
                        context.startActivity(
                            android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    isShizukuGranted = shizukuGranted,
                    onRequestShizuku = {
                        runCatching { graph.shizukuExecutor.requestPermission() }
                    },
                    isRootAvailable = rootAvailable,
                    bgStartVisible = bgStartVisible,
                    bgStartAllowed = bgStartAllowed,
                    bgStartCommand = bgStartCommand,
                    canFixBgStart = shizukuGranted || rootAvailable,
                    onFixBgStart = {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            graph.fixBgStart()
                        }
                        bgStartAllowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            !ru.outsideproarts.oassist.core.agent.BgStartGuard.isOpRestricted(context)
                        }
                        result.exceptionOrNull()?.message
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TopDest.Settings.route) {
                val context = LocalContext.current
                var showWipeDialog by remember { mutableStateOf(false) }
                val backup = remember { ru.outsideproarts.oassist.core.data.backup.BackupManager(graph.data) }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val json = runCatching { backup.export() }.getOrElse { null }
                        if (json == null) {
                            showToast(context, SettingsR.string.data_import_error, "?")
                            return@launch
                        }
                        val ok = runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(json.toByteArray(Charsets.UTF_8))
                            } ?: throw IllegalStateException("Нет потока записи")
                        }.isSuccess
                        if (ok) showToast(context, SettingsR.string.data_export_done)
                        else showToast(context, SettingsR.string.data_import_error, "?")
                    }
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val raw = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                input.readBytes().toString(Charsets.UTF_8)
                            }
                        }.getOrNull()
                        if (raw == null) {
                            showToast(context, SettingsR.string.data_import_error, "?")
                            return@launch
                        }
                        backup.import(raw).fold(
                            onSuccess = {
                                showToast(context, SettingsR.string.data_import_done)
                            },
                            onFailure = {
                                val reason = it.message?.take(200) ?: "?"
                                showToast(context, SettingsR.string.data_import_error, reason)
                            },
                        )
                    }
                }
                SettingsScreen(
                    language = language,
                    onLanguageChange = onLanguageChange,
                    onNavigateToProviders = { navController.navigate("provider_list") },
                    onNavigateToModeConfig = { mode -> navController.navigate("mode_config/$mode") },
                    onNavigateToTools = { navController.navigate("tools_settings") },
                    onNavigateToSearch = { navController.navigate("search_settings") },
                    onNavigateToMcp = { navController.navigate("mcp_servers") },
                    onNavigateToSkills = { navController.navigate("skills") },
                    onNavigateToMemory = { navController.navigate("memory") },
                    onNavigateToAssistant = { navController.navigate("assistant_access") },
                    onExport = { exportLauncher.launch("oassist-backup.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json")) },
                    onWipe = { showWipeDialog = true },
                )
                if (showWipeDialog) {
                    AlertDialog(
                        onDismissRequest = { showWipeDialog = false },
                        title = { Text(stringResource(SettingsR.string.data_wipe_title)) },
                        text = { Text(stringResource(SettingsR.string.data_wipe_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showWipeDialog = false
                                scope.launch {
                                    backup.wipe().fold(
                                        onSuccess = {
                                            showToast(context, SettingsR.string.data_wipe_done)
                                        },
                                        onFailure = {
                                            val reason = it.message?.take(200) ?: "?"
                                            showToast(context, SettingsR.string.data_import_error, reason)
                                        },
                                    )
                                }
                            }) {
                                Text(stringResource(SettingsR.string.data_wipe_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWipeDialog = false }) {
                                Text(stringResource(SettingsR.string.action_cancel))
                            }
                        },
                    )
                }
            }
            composable("skills") {
                var version by remember { mutableStateOf(0) }
                var skills by remember {
                    mutableStateOf(emptyList<ru.outsideproarts.oassist.core.model.SkillInfo>())
                }
                LaunchedEffect(version) {
                    skills = runCatching { graph.data.skills.list() }.getOrElse { emptyList() }
                }
                ru.outsideproarts.oassist.feature.settings.SkillsScreen(
                    skills = skills,
                    onSetEnabled = { name, enabled ->
                        scope.launch {
                            graph.data.skills.setEnabled(name, enabled)
                            version++
                        }
                    },
                    onSave = { name, title, body ->
                        scope.launch {
                            runCatching { graph.data.skills.upsert(name, title, body) }
                            version++
                        }
                    },
                    onDelete = { name ->
                        scope.launch {
                            graph.data.skills.delete(name)
                            version++
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("memory") {
                val context = LocalContext.current
                var text by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    text = runCatching { graph.data.memory.read() }.getOrElse { "" }
                }
                ru.outsideproarts.oassist.feature.settings.MemoryScreen(
                    text = text,
                    onSave = { updated ->
                        scope.launch {
                            graph.data.memory.write(updated)
                            text = updated
                            showToast(context, R.string.memory_saved)
                        }
                    },
                    onClear = {
                        scope.launch {
                            graph.data.memory.clear()
                            text = ""
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("search_settings") {
                val searchEngine by graph.data.searchConfig.engine
                    .collectAsStateWithLifecycle(
                        initialValue = ru.outsideproarts.oassist.core.model.SearchEngine.DEFAULT,
                    )
                ru.outsideproarts.oassist.feature.settings.SearchSettingsScreen(
                    engine = searchEngine,
                    onEngineChange = { engine ->
                        scope.launch { graph.data.searchConfig.setEngine(engine) }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("tools_settings") {
                val prefs by graph.data.toolConfigs.preferences
                    .collectAsStateWithLifecycle(
                        initialValue = ru.outsideproarts.oassist.core.model.ToolPreferences(),
                    )
                val context = LocalContext.current
                val approvalVersion = remember { mutableStateOf(0) }
                val rememberedApprovals = remember(approvalVersion.value) {
                    graph.approvalPolicy.remembered()
                }
                val matchedPreset = remember(approvalVersion.value) {
                    graph.approvalPolicy.matchesPreset()
                }
                ru.outsideproarts.oassist.feature.settings.ToolsSettingsScreen(
                    tools = ru.outsideproarts.oassist.core.agent.ToolCatalog.descriptors(),
                    disabled = prefs.disabled,
                    rememberedApprovals = rememberedApprovals,
                    matchedPreset = matchedPreset,
                    maxToolRounds = prefs.maxToolRounds,
                    onToggleEnabled = { name, enabled ->
                        scope.launch { graph.data.toolConfigs.setToolEnabled(name, enabled) }
                    },
                    onApplyPreset = { preset ->
                        graph.approvalPolicy.applyPreset(preset)
                        approvalVersion.value++
                    },
                    onResetGroup = { groupKey ->
                        graph.approvalPolicy.setRemembered(
                            groupKey,
                            ru.outsideproarts.oassist.core.model.ApprovalState.ASK,
                        )
                        approvalVersion.value++
                    },
                    onMaxToolRoundsChange = { rounds ->
                        scope.launch { graph.data.toolConfigs.setMaxToolRounds(rounds) }
                    },
                    onResetApprovals = {
                        graph.approvalPolicy.clearRemembered()
                        approvalVersion.value++
                        showToast(context, R.string.approvals_reset_done)
                    },
                    onResetTools = {
                        scope.launch {
                            graph.data.toolConfigs.resetAll()
                            showToast(context, R.string.tools_reset_done)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("mcp_servers") {
                val servers by graph.data.mcpServers.observeServers()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val context = LocalContext.current
                var testingId by remember { mutableStateOf<String?>(null) }
                ru.outsideproarts.oassist.feature.settings.McpServersScreen(
                    servers = servers,
                    testingId = testingId,
                    onSave = { updated, secrets ->
                        scope.launch {
                            graph.data.mcpServers.upsert(updated)
                            val bearer = secrets.bearer
                            when {
                                secrets.clearBearer -> graph.data.mcpServers.clearBearer(updated.id)
                                bearer != null -> graph.data.mcpServers.setBearer(updated.id, bearer.toCharArray())
                            }
                        }
                    },
                    onDelete = { id ->
                        scope.launch { graph.data.mcpServers.delete(id) }
                    },
                    onSetEnabled = { id, enabled ->
                        scope.launch { graph.data.mcpServers.setEnabled(id, enabled) }
                    },
                    onTest = { id ->
                        scope.launch {
                            testingId = id
                            val config = servers.find { it.id == id }
                            if (config == null) {
                                showToast(context, R.string.models_provider_not_found)
                            } else {
                                runCatching {
                                    val headers = graph.data.mcpServers.resolveHeaders(config)
                                    val client = ru.outsideproarts.oassist.core.agent.mcp.McpClient(
                                        graph.httpClient,
                                        config.url,
                                        headers,
                                    )
                                    kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                                        client.listTools()
                                    } ?: throw java.io.IOException("timeout")
                                }.onSuccess { tools ->
                                    showToast(context, R.string.mcp_test_success, config.name, tools.size)
                                }.onFailure { e ->
                                    val detail = (e as? ru.outsideproarts.oassist.core.agent.mcp.McpError)?.message
                                        ?: ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
                                            .userMessage(context)
                                    showToast(
                                        context,
                                        R.string.mcp_test_error,
                                        config.name,
                                        detail,
                                        duration = android.widget.Toast.LENGTH_LONG,
                                    )
                                }
                            }
                            testingId = null
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("provider_list") {                val providers by graph.providers.observeProviders()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                ProviderListScreen(
                    providers = providers,
                    onAddProvider = { navController.navigate("provider_edit/new") },
                    onEditProvider = { id -> navController.navigate("provider_edit/$id") },
                )
            }
            composable(
                "provider_edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")
                val providers by graph.providers.observeProviders()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val profile = providers.find { it.id == id }
                val context = LocalContext.current
                var isSaving by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf<String?>(null) }
                ProviderEditScreen(
                    profile = profile,
                    isSaving = isSaving,
                    saveError = saveError,
                    onDismissSaveError = { saveError = null },
                    onSave = { updated, secrets ->
                        scope.launch {
                            isSaving = true
                            saveError = null
                            saveProvider(graph, updated, secrets)
                                .onSuccess { result ->
                                    if (result is ProviderValidationResult.Skipped) {
                                        showToast(context, R.string.provider_save_no_check)
                                    }
                                    navController.popBackStack()
                                }
                                .onFailure { e ->
                                    saveError = ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
                                        .userMessage(context)
                                }
                            isSaving = false
                        }
                    },
                    onDelete = { providerId ->
                        scope.launch {
                            graph.providers.deleteProvider(providerId)
                            navController.popBackStack()
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                "mode_config/{mode}",
                arguments = listOf(navArgument("mode") { type = NavType.StringType }),
            ) { backStackEntry ->
                val modeName = backStackEntry.arguments?.getString("mode") ?: "CHAT"
                val mode = AssistMode.valueOf(modeName)
                val modeConfigs by graph.providers.observeModeConfigs()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val providers by graph.providers.observeProviders()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val config = modeConfigs.find { it.mode == mode } ?: ModeConfig.default(mode)
                val context = LocalContext.current
                var models by remember { mutableStateOf(emptyList<ru.outsideproarts.oassist.core.model.ModelInfo>()) }
                var isLoadingModels by remember { mutableStateOf(false) }
                var isProbing by remember { mutableStateOf(false) }

                LaunchedEffect(config.providerId) {
                    val providerId = config.providerId
                    models = if (providerId == null) {
                        emptyList()
                    } else {
                        graph.providers.observeModelCatalog(providerId).first()?.models ?: emptyList()
                    }
                }

                ModeConfigScreen(
                    mode = mode,
                    config = config,
                    providers = providers,
                    models = models,
                    isLoadingModels = isLoadingModels,
                    loadDefaultPrompt = { graph.defaultPromptText(mode) },
                    onSave = { updated ->
                        scope.launch {
                            graph.providers.setModeConfig(updated)
                            navController.popBackStack()
                        }
                    },
                    onRefreshModels = { providerId ->
                        scope.launch {
                            isLoadingModels = true
                            val profile = providers.find { it.id == providerId }
                            if (profile == null) {
                                showToast(context, R.string.models_provider_not_found)
                                models = emptyList()
                            } else {
                                runCatching {
                                    val client = graph.modelClientFor(profile)
                                    val fetched = client.fetchModels()
                                    graph.providers.refreshModelCatalog(providerId, fetched)
                                    fetched
                                }.onSuccess { fetched ->
                                    models = fetched
                                    showToast(context, R.string.models_refresh_success, fetched.size)
                                }.onFailure { e ->
                                    models = emptyList()
                                    val message = ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
                                        .userMessage(context)
                                    showToast(
                                        context,
                                        R.string.models_refresh_error,
                                        message,
                                        duration = android.widget.Toast.LENGTH_LONG,
                                    )
                                }
                            }
                            isLoadingModels = false
                        }
                    },
                    onProviderSelected = { selectedId ->
                        scope.launch {
                            models = graph.providers.observeModelCatalog(selectedId).first()?.models
                                ?: emptyList()
                        }
                    },
                    isProbing = isProbing,
                    onProbeModel = { probeProviderId, probeModelId ->
                        scope.launch {
                            isProbing = true
                            val profile = providers.find { it.id == probeProviderId }
                            if (profile == null) {
                                showToast(context, R.string.models_provider_not_found)
                            } else {
                                runCatching {
                                    val client = graph.modelClientFor(profile)
                                    val caps = client.probeCapabilities(probeModelId)
                                    graph.providers.updateModelCapabilities(probeProviderId, probeModelId, caps)
                                    caps
                                }.onSuccess { caps ->
                                    models = graph.providers.observeModelCatalog(probeProviderId).first()?.models ?: emptyList()
                                    showToast(
                                        context,
                                        if (ru.outsideproarts.oassist.core.model.ModelCapability.TOOLS in caps) {
                                            R.string.models_probe_tools
                                        } else {
                                            R.string.models_probe_no_tools
                                        },
                                    )
                                }.onFailure { e ->
                                    val message = ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
                                        .userMessage(context)
                                    showToast(
                                        context,
                                        R.string.models_refresh_error,
                                        message,
                                        duration = android.widget.Toast.LENGTH_LONG,
                                    )
                                }
                            }
                            isProbing = false
                        }
                    },
                    onSaveCapabilities = { capsProviderId, capsModelId, caps ->
                        scope.launch {
                            graph.providers.updateModelCapabilities(capsProviderId, capsModelId, caps)
                            models = graph.providers.observeModelCatalog(capsProviderId).first()?.models ?: emptyList()
                        }
                    },
                    onSaveProtocol = { protoProviderId, protoModelId, protocol ->
                        scope.launch {
                            graph.providers.updateModelProtocol(protoProviderId, protoModelId, protocol)
                            models = graph.providers.observeModelCatalog(protoProviderId).first()?.models ?: emptyList()
                        }
                    },
                    loadVoices = { pkg -> graph.speechOutput.availableVoices(pkg) },
                    loadEngines = { graph.speechOutput.availableEngines() },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    AnnouncerHost(announcer = announcer)
}

/**
 * Одноразовый тост из обработчика события. Строка резолвится через [android.content.Context.getString]
 * в момент клика (а не при композиции), поэтому хелпер — вне @Composable: так не срабатывает
 * `LocalContextGetResourceValueCall` и текст всегда в актуальной локали.
 */
private fun showToast(
    context: android.content.Context,
    resId: Int,
    vararg args: Any,
    duration: Int = android.widget.Toast.LENGTH_SHORT,
) {
    android.widget.Toast.makeText(context, context.getString(resId, *args), duration).show()
}

/**
 * Проверка параметров провайдера и сохранение. ANTHROPIC/GEMINI — заглушка (без пробы),
 * результат возвращается как успех с [ProviderValidationResult.Skipped] — вызывающий
 * показывает подсказку. Ошибка несохранённых секретов — только через [ProviderAuth].
 */
private suspend fun saveProvider(
    graph: AppGraph,
    updated: ru.outsideproarts.oassist.core.model.ProviderProfile,
    secrets: ProviderSecretsInput,
): Result<ProviderValidationResult> = try {
    val overrides = ProviderAuth(
        bearerToken = secrets.apiKey?.toCharArray(),
    )
    val validator = ProviderValidator(graph.data.secretStore, graph.httpClient)
    when (val result = validator.validate(updated, overrides)) {
        is ProviderValidationResult.Failure -> Result.failure(result.error)
        else -> {
            graph.providers.upsertProvider(updated)
            val apiKey = secrets.apiKey
            when {
                secrets.clearApiKey -> graph.providers.clearApiKey(updated.id)
                apiKey != null -> graph.providers.setApiKey(updated.id, apiKey.toCharArray())
            }
            if (result is ProviderValidationResult.Success) {
                graph.providers.refreshModelCatalog(updated.id, result.models)
            }
            Result.success(result)
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
