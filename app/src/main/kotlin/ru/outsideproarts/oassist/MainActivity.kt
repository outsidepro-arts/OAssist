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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
    var openUpdatesRequested by rememberSaveable { mutableStateOf(false) }
    ru.outsideproarts.oassist.update.UpdateHost(graph.updates) {
        openUpdatesRequested = true
    }
    LaunchedEffect(openUpdatesRequested, backStack) {
        if (openUpdatesRequested && backStack != null) {
            navController.navigate("updates") { launchSingleTop = true }
            openUpdatesRequested = false
        }
    }

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
            composable(TopDest.Chat.route) { chatEntry ->
                val modeConfigs by graph.providers.observeModeConfigs()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val chatConfig = modeConfigs.find { it.mode == AssistMode.CHAT }
                val isSpeaking by graph.speechOutput.speaking.collectAsStateWithLifecycle()
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
                    sendMessage = { mode, userMsg, history, audio, onTurnMessages, onPinChange ->
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
                        // Агент собирается общей фабрикой (общий каталог инструментов,
                        // лимит шагов, fallback bye) — как в SHARE/ASSISTANT.
                        val agent = ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                            .agentFor(profile, mode)
                        val prompt = graph.buildSystemPrompt(mode)
                        ru.outsideproarts.oassist.core.agent.AgentSession(
                            chunks = agent.stream(
                                modelId = modelId,
                                systemPrompt = prompt.instructions,
                                promptContext = prompt.context,
                                history = history,
                                userMessage = userMsg,
                                audio = audio,
                                onTurnMessages = onTurnMessages,
                                onPinChange = onPinChange,
                            ),
                            agent = agent,
                        )
                    },
                    speakSentence = { graph.speechOutput.speak(it) },
                    stopSpeech = { graph.speechOutput.stop() },
                    isSpeaking = isSpeaking,
                    speakToolCalls = chatConfig?.effectiveTts()?.speakToolCalls ?: true,
                    soundEnabled = chatConfig?.effectiveSoundEnabled() ?: true,
                    stt = chatConfig?.effectiveStt() ?: ru.outsideproarts.oassist.core.model.SttConfig.DEFAULT,
                    audioIn = chatAudioIn,
                    registerDestinationExit = remember(navController, chatEntry) {
                        { onExit -> observeChatDestinationExit(navController, chatEntry, onExit) }
                    },
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
                val writeSettingsGranted = remember(accessVersion) {
                    android.provider.Settings.System.canWrite(context)
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
                    isWriteSettingsGranted = writeSettingsGranted,
                    onOpenWriteSettings = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
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
                val importUnfinished by graph.data.settings.importUnfinished.collectAsStateWithLifecycle(initialValue = false)
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val json = try {
                            backup.export()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            showToast(context, SettingsR.string.data_export_error)
                            return@launch
                        }
                        val ok = runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(json.toByteArray(Charsets.UTF_8))
                            } ?: throw IllegalStateException("Нет потока записи")
                        }.isSuccess
                        if (ok) showToast(context, SettingsR.string.data_export_done)
                        else showToast(context, SettingsR.string.data_export_error)
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
                                if (it is ru.outsideproarts.oassist.core.data.backup.PartialBackupImportException) {
                                    showToast(context, SettingsR.string.data_import_unfinished)
                                } else {
                                    val reason = it.message?.take(200) ?: "?"
                                    showToast(context, SettingsR.string.data_import_error, reason)
                                }
                            },
                        )
                    }
                }
                SettingsScreen(
                    importUnfinished = importUnfinished,
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
                    onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                    onNavigateToUpdates = { navController.navigate("updates") },
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
                                            showToast(context, SettingsR.string.data_wipe_error)
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
            composable("updates") {
                ru.outsideproarts.oassist.update.UpdatesRoute(graph.updates) { navController.popBackStack() }
            }
            composable("skills") {
                val context = LocalContext.current
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
                            try {
                                graph.data.skills.delete(name)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                showToast(context, SettingsR.string.skill_delete_error)
                            }
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
                            try {
                                graph.data.memory.clear()
                                text = ""
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                showToast(context, SettingsR.string.memory_clear_error)
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("diagnostics") {
                ru.outsideproarts.oassist.feature.settings.DiagnosticsScreen(
                    store = graph.data.diagnostics,
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
                val approvalPersistenceFailed by graph.approvalPolicy.persistenceFailed.collectAsStateWithLifecycle()
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
                    approvalPersistenceFailed = approvalPersistenceFailed,
                    onRetryApprovalPersistence = graph.approvalPolicy::retryPersistence,
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
                    editorEpoch = graph.data.mcpServers::editorEpoch,
                    onSave = { updated, secrets, epoch, original ->
                        scope.launch {
                            try {
                                graph.data.mcpServers.saveEditor(updated, secrets.bearer?.toCharArray(), secrets.clearBearer, epoch, original)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: ru.outsideproarts.oassist.core.data.mcp.McpEditorInvalidatedException) {
                                showToast(context, SettingsR.string.mcp_editor_invalidated)
                            } catch (error: Exception) {
                                showToast(context, SettingsR.string.mcp_save_failed)
                            }
                        }
                    },
                    onDelete = { id ->
                        scope.launch { graph.data.mcpServers.delete(id) }
                    },
                    onSetEnabled = { server, enabled, epoch ->
                        scope.launch {
                            try {
                                graph.data.mcpServers.setEnabled(server, enabled, epoch)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: ru.outsideproarts.oassist.core.data.mcp.McpEditorInvalidatedException) {
                                showToast(context, SettingsR.string.mcp_toggle_invalidated)
                            } catch (error: Exception) {
                                showToast(context, SettingsR.string.mcp_save_failed)
                            }
                        }
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
                                        ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
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
                val editorOwner = rememberProviderEditorOwner(backStackEntry)
                var isSaving by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf<String?>(null) }
                var pendingHeaderCleanup by remember { mutableStateOf<Set<String>>(emptySet()) }
                var cleanupWarningReady by remember { mutableStateOf(false) }
                val isCurrentEditor = { navController.currentBackStackEntry == backStackEntry }
                ProviderEditScreen(
                    profile = profile,
                    sourceProviderId = id?.takeUnless { it == "new" },
                    isSaving = isSaving,
                    saveError = saveError,
                    onDismissSaveError = { saveError = null },
                    onSave = { updated, secrets, originalId, updateDraftRevision ->
                        editorOwner.launch {
                            isSaving = true
                            saveError = null
                            cleanupWarningReady = false
                            try {
                                saveProvider(
                                    graph.providers,
                                    ProviderValidator(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, graph.data.secretStore, graph.httpClient),
                                    graph.data.secretStore, updated, secrets,
                                    pendingCleanup = pendingHeaderCleanup,
                                    originalProviderId = originalId,
                                    onCredentialRevisionChanged = { revision, exists -> if (isCurrentEditor()) updateDraftRevision(revision, exists) },
                                    onCleanupPending = { if (isCurrentEditor()) pendingHeaderCleanup = it },
                                )
                                    .onSuccess { result ->
                                        if (!isCurrentEditor()) return@onSuccess
                                        if (result is ProviderValidationResult.Skipped) {
                                            showToast(context, R.string.provider_save_no_check)
                                        }
                                        if (pendingHeaderCleanup.isEmpty()) navController.popBackStack()
                                        else cleanupWarningReady = true
                                    }
                                    .onFailure { e ->
                                        if (!isCurrentEditor()) return@onFailure
                                        saveError = ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
                                            .userMessage(context)
                                    }
                            } finally {
                                isSaving = false
                            }
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
                if (cleanupWarningReady && pendingHeaderCleanup.isNotEmpty() && !isSaving) {
                    AlertDialog(
                        onDismissRequest = { navController.popBackStack() },
                        title = { Text(stringResource(R.string.provider_cleanup_warning_title)) },
                        text = { Text(stringResource(R.string.provider_cleanup_warning)) },
                        confirmButton = {
                            TextButton(onClick = {
                                editorOwner.launch {
                                    isSaving = true
                                    try {
                                        retryProviderHeaderCleanup(
                                            graph.providers, graph.data.secretStore, pendingHeaderCleanup,
                                            isCurrentEditor,
                                            onPending = { pendingHeaderCleanup = it },
                                            onDone = { navController.popBackStack() },
                                        )
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // Keep the dialog and remaining aliases available for another retry.
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }) { Text(stringResource(R.string.provider_cleanup_retry)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { navController.popBackStack() }) {
                                Text(stringResource(R.string.provider_cleanup_close))
                            }
                        },
                    )
                }
            }
            composable(
                "mode_config/{mode}",
                arguments = listOf(navArgument("mode") { type = NavType.StringType }),
            ) { backStackEntry ->
                val modeName = backStackEntry.arguments?.getString("mode") ?: "CHAT"
                val mode = AssistMode.valueOf(modeName)
                // null — конфиги ещё грузятся: экран не синхронизирует поля формы
                // и не затирает ввод при догрузке/восстановлении.
                val modeConfigs by graph.providers.observeModeConfigs()
                    .collectAsStateWithLifecycle(initialValue = null as List<ModeConfig>?)
                val providers by graph.providers.observeProviders()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val config = modeConfigs?.let { configs ->
                    configs.find { it.mode == mode } ?: ModeConfig.default(mode)
                }
                val context = LocalContext.current
                val screenScope = rememberCoroutineScope()
                val catalog = remember {
                    ModeCatalogController(
                        screenScope,
                        load = { id -> graph.providers.modelsForSelection(id) },
                    )
                }
                DisposableEffect(catalog) { onDispose { catalog.close() } }

                ModeConfigScreen(
                    mode = mode,
                    config = config,
                    providers = providers,
                    models = catalog.models,
                    isLoadingModels = catalog.isLoading,
                    loadDefaultPrompt = { graph.defaultPromptText(mode) },
                    onSave = { updated ->
                        scope.launch {
                            graph.providers.setModeConfig(updated)
                            navController.popBackStack()
                        }
                    },
                    onRefreshModels = { providerId ->
                        catalog.refresh(providerId, operation = {
                            val profile = providers.find { it.id == providerId }
                                ?: throw CatalogProviderMissing()
                            val fetched = graph.modelClientFor(profile).fetchModels()
                            graph.providers.refreshModelCatalog(profile, fetched)
                            fetched
                        }, onSuccess = { fetched ->
                            showToast(context, R.string.models_refresh_success, fetched.size)
                        }, onFailure = { e ->
                            if (e is CatalogProviderMissing) {
                                showToast(context, R.string.models_provider_not_found)
                            } else {
                                val message = ru.outsideproarts.oassist.core.provider.ProviderError.from(e).userMessage(context)
                                showToast(context, R.string.models_refresh_error, message, duration = android.widget.Toast.LENGTH_LONG)
                            }
                        })
                    },
                    onProviderSelected = { catalog.select(it) },
                    isProbing = catalog.isProbing,
                    onProbeModel = { providerId, modelId ->
                        var hasTools = false
                        catalog.probe(providerId, operation = {
                            val profile = providers.find { it.id == providerId }
                                ?: throw CatalogProviderMissing()
                            val caps = graph.modelClientFor(profile).probeCapabilities(modelId)
                            graph.providers.updateModelCapabilities(providerId, modelId, caps)
                            hasTools = ru.outsideproarts.oassist.core.model.ModelCapability.TOOLS in caps
                            graph.providers.modelsForSelection(providerId)
                        }, onSuccess = {
                            showToast(context, if (hasTools) R.string.models_probe_tools else R.string.models_probe_no_tools)
                        }, onFailure = { e ->
                            if (e is CatalogProviderMissing) {
                                showToast(context, R.string.models_provider_not_found)
                            } else {
                                val message = ru.outsideproarts.oassist.core.provider.ProviderError.from(e).userMessage(context)
                                showToast(context, R.string.models_refresh_error, message, duration = android.widget.Toast.LENGTH_LONG)
                            }
                        })
                    },
                    onSaveCapabilities = { providerId, modelId, caps ->
                        catalog.update(providerId, onFailure = {
                            showToast(context, R.string.models_save_error, duration = android.widget.Toast.LENGTH_LONG)
                        }) {
                            graph.providers.updateModelCapabilities(providerId, modelId, caps)
                            graph.providers.modelsForSelection(providerId)
                        }
                    },
                    onSaveProtocol = { providerId, modelId, protocol ->
                        catalog.update(providerId, onFailure = {
                            showToast(context, R.string.models_save_error, duration = android.widget.Toast.LENGTH_LONG)
                        }) {
                            graph.providers.updateModelProtocol(providerId, modelId, protocol)
                            graph.providers.modelsForSelection(providerId)
                        }
                    },
                    loadVoices = { pkg -> graph.speechOutput.availableVoices(pkg) },
                    loadEngines = { graph.speechOutput.availableEngines() },
                    loadRecognitionServices = { graph.recognitionServices.availableServices() },
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
 * Проверка профиля провайдера и списка его моделей перед сохранением. Переопределение
 * авторизации передаётся как [ProviderAuth]; результат проверки сохраняется отдельно
 * от значения секрета.
 */
internal suspend fun saveProvider(
    providers: ru.outsideproarts.oassist.core.data.provider.ProviderRepository,
    validator: ProviderValidator,
    secretStore: ru.outsideproarts.oassist.core.data.secret.SecretStore,
    updated: ru.outsideproarts.oassist.core.model.ProviderProfile,
    secrets: ProviderSecretsInput,
    pendingCleanup: Set<String> = emptySet(),
    originalProviderId: String? = updated.id,
    onCredentialRevisionChanged: (String?, Boolean) -> Unit = { _, _ -> },
    onCleanupPending: (Set<String>) -> Unit = {},
): Result<ProviderValidationResult> {
    val validatedCredentialRevision = updated.credentialRevision
    val credentialToken = ru.outsideproarts.oassist.core.data.provider.CredentialUpdateToken(updated.id, validatedCredentialRevision, originalProviderId != null)
    return try {
    if (originalProviderId != null && originalProviderId != updated.id) throw ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException()
    // Validation sees the desired credentials; persistence retains the original
    // references until clear* has removed their secrets successfully.
    val validationProfile = updated.copy(
        apiKeyRef = updated.apiKeyRef.takeUnless { secrets.clearApiKey },
        basicUserRef = updated.basicUserRef.takeUnless { secrets.clearBasic },
        basicPasswordRef = updated.basicPasswordRef.takeUnless { secrets.clearBasic },
    )
    val overrides = ProviderAuth(
        apiKey = secrets.apiKey?.takeUnless { secrets.clearApiKey }?.toCharArray(),
        basicUser = secrets.basicUser.takeUnless { secrets.clearBasic },
        basicPassword = secrets.basicPassword?.takeUnless { secrets.clearBasic }?.toCharArray(),
        extraHeaders = secrets.extraHeaders,
    )
    val validation = try {
        providers.withCredentialSnapshot(updated.id, validatedCredentialRevision, originalProviderId != null) { snapshot ->
            validator.validate(validationProfile, overrides, snapshot)
        }
    } finally {
        overrides.zeroOut()
    }
    when (val result = validation) {
        is ProviderValidationResult.Failure -> Result.failure(result.error)
        else -> {
            val catalogCredentialsUnchanged = providers.getProvider(updated.id)?.credentialRevision == validatedCredentialRevision
            if (!catalogCredentialsUnchanged) throw ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException()
            val oldHeaders = providers.getProvider(updated.id)?.extraHeaders.orEmpty()
            var removedAliases = pendingCleanup + secrets.clearedExtraHeaders.mapNotNull(oldHeaders::get)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                providers.upsertProvider(updated, credentialToken)
                onCleanupPending(removedAliases)
            }
            val apiKey = secrets.apiKey
            when {
                secrets.clearApiKey -> providers.clearApiKey(updated.id, credentialToken)
                apiKey != null -> providers.setApiKey(updated.id, apiKey.toCharArray(), credentialToken)
            }
            val basicUser = secrets.basicUser
            val basicPassword = secrets.basicPassword
            when {
                secrets.clearBasic -> providers.clearBasicCredentials(updated.id, credentialToken)
                // Парная запись: полная пара или ничего (частичный ввод не сохраняется).
                basicUser != null && basicPassword != null -> {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        val retired = providers.setBasicCredentials(
                            updated.id, basicUser.toCharArray(), basicPassword.toCharArray(), credentialToken,
                        )
                        removedAliases = removedAliases + retired
                        onCleanupPending(removedAliases)
                    }
                }
            }
            secrets.extraHeaders.forEach { (headerName, value) ->
                providers.setExtraHeader(updated.id, headerName, value.toCharArray(), credentialToken)
            }
            if (result is ProviderValidationResult.Success) {
                val saved = providers.getProvider(updated.id)
                    ?: throw ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException()
                // Secret writes may allocate aliases, but must not rebind a validation
                // result to a concurrently edited endpoint or catalog configuration.
                if (saved.extraHeaders.keys != updated.extraHeaders.keys + secrets.extraHeaders.keys) {
                    throw ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException()
                }
                val catalogProfile = updated.copy(
                    apiKeyRef = saved.apiKeyRef,
                    basicUserRef = saved.basicUserRef,
                    basicPasswordRef = saved.basicPasswordRef,
                    extraHeaders = saved.extraHeaders,
                    credentialRevision = credentialToken.revision,
                )
                providers.refreshModelCatalog(catalogProfile, result.models, rejectStale = true)
            }
            try {
                cleanupProviderHeaders(providers, secretStore, removedAliases, onCleanupPending)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The saved profile no longer references the retired headers.
            }
            Result.success(result)
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
} finally {
    onCredentialRevisionChanged(credentialToken.retryRevision, credentialToken.expectedExists == true)
}
}

internal suspend fun retryProviderHeaderCleanup(
    providers: ru.outsideproarts.oassist.core.data.provider.ProviderRepository,
    secretStore: ru.outsideproarts.oassist.core.data.secret.SecretStore,
    aliases: Set<String>,
    isCurrentEditor: () -> Boolean,
    onPending: (Set<String>) -> Unit,
    onDone: () -> Unit,
) {
    cleanupProviderHeaders(providers, secretStore, aliases) { remaining ->
        if (isCurrentEditor()) onPending(remaining)
    }
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (isCurrentEditor()) onDone()
}

internal class ProviderEditorOwner(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var operation: kotlinx.coroutines.Job? = null

    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        operation = scope.launch(block = block)
    }

    fun cancel() { operation?.cancel() }
}

@Composable
internal fun rememberProviderEditorOwner(entry: androidx.navigation.NavBackStackEntry): ProviderEditorOwner {
    val scope = rememberCoroutineScope()
    val owner = remember(entry) { ProviderEditorOwner(scope) }
    DisposableEffect(entry, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) owner.cancel()
        }
        entry.lifecycle.addObserver(observer)
        onDispose {
            entry.lifecycle.removeObserver(observer)
            owner.cancel()
        }
    }
    return owner
}

internal suspend fun cleanupProviderHeaders(
    providers: ru.outsideproarts.oassist.core.data.provider.ProviderRepository,
    secretStore: ru.outsideproarts.oassist.core.data.secret.SecretStore,
    aliases: Set<String>,
    onPending: (Set<String>) -> Unit,
) {
    var pending = aliases
    for (alias in aliases) {
        val active = providers.observeProviders().first().any { profile ->
            alias in profile.extraHeaders.values || alias == profile.apiKeyRef ||
                alias == profile.basicUserRef || alias == profile.basicPasswordRef
        }
        if (!active) secretStore.delete(alias)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        pending = pending - alias
        onPending(pending)
    }
}
