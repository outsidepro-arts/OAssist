package ru.outsideproarts.oassist.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistDropdownClassName
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistMergedContent
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistSlider
import ru.outsideproarts.oassist.core.a11y.oassistSwitchRow
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.AssistantExtras
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ShareExtras
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.SpeechOutputChannel
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.model.SttEngine
import ru.outsideproarts.oassist.core.model.TtsConfig
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.speech.BtInputDecision
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.VoiceInfo
import ru.outsideproarts.oassist.core.speech.TtsEngineInfo
import ru.outsideproarts.oassist.core.speech.RecognitionServiceInfo
import ru.outsideproarts.oassist.core.speech.voiceLanguageTags
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistLiveStatus
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeConfigScreen(
    mode: AssistMode,
    config: ModeConfig,
    providers: List<ProviderProfile>,
    models: List<ModelInfo>,
    isLoadingModels: Boolean,
    onSave: (ModeConfig) -> Unit,
    onRefreshModels: (providerId: String) -> Unit,
    onProviderSelected: (providerId: String) -> Unit,
    isProbing: Boolean,
    onProbeModel: (providerId: String, modelId: String) -> Unit,
    onSaveCapabilities: (providerId: String, modelId: String, capabilities: Set<ModelCapability>) -> Unit,
    onSaveProtocol: (providerId: String, modelId: String, protocol: ModelProtocol?) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Установленные синтезаторы; пусто — список недоступен (только дефолт). */
    loadEngines: suspend () -> List<TtsEngineInfo> = { emptyList() },
    /** Голоса движка (`null` — привязанный/дефолт); пусто — только голос по умолчанию. */
    loadVoices: suspend (enginePackage: String?) -> List<VoiceInfo> = { emptyList() },
    /** Установленные Android RecognitionService. */
    loadRecognitionServices: () -> List<RecognitionServiceInfo> = { emptyList() },
    /** Дефолтный промпт режима из assets в языке приложения; пусто — недоступен. */
    loadDefaultPrompt: suspend () -> String = { "" },
) {
    var providerId by remember { mutableStateOf(config.providerId ?: "") }
    var modelId by remember { mutableStateOf(config.modelId ?: "") }
    var initialPrompt by remember { mutableStateOf(config.initialPrompt ?: "") }
    // Дефолтный текст для предзаполнения поля, кнопки «Восстановить»
    // и подстановки при сохранении пустого поля. null — ещё не загружен.
    var defaultPromptText by remember(mode) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var providerExpanded by remember { mutableStateOf(false) }
    // ID модели — обычное поле ввода (ручной ввод); выбор из списка —
    // отдельной кнопкой «Выбрать модель» с диалогом-пикером ниже.
    var modelPickerOpen by remember { mutableStateOf(false) }
    var autoSpeak by remember { mutableStateOf(config.assistant?.autoSpeak ?: true) }
    var autoClose by remember { mutableStateOf(config.assistant?.autoClose ?: true) }
    var attachScreenshot by remember { mutableStateOf(config.assistant?.attachScreenshot ?: true) }
    var continueDialog by remember { mutableStateOf(config.assistant?.continueDialog ?: false) }
    var showOverlay by remember { mutableStateOf(config.assistant?.showOverlay ?: true) }
    var sharedAction by remember { mutableStateOf(config.share?.sharedContentAction ?: "") }
    var showInChat by remember { mutableStateOf(config.share?.showInChat ?: true) }
    val baseTts = config.effectiveTts()
    var ttsChannel by remember { mutableStateOf(baseTts.channel) }
    var ttsRate by remember { mutableStateOf(baseTts.rate) }
    var ttsPitch by remember { mutableStateOf(baseTts.pitch) }
    var ttsVolume by remember { mutableStateOf(baseTts.volume) }
    var ttsVoice by remember { mutableStateOf(baseTts.voiceName ?: "") }
    var ttsEngine by remember { mutableStateOf(baseTts.enginePackage ?: "") }
    var ttsVoiceLocale by remember { mutableStateOf("") }
    var ttsToolCalls by remember { mutableStateOf(baseTts.speakToolCalls) }
    var ttsNarrative by remember { mutableStateOf(baseTts.narrative) }
    // Текущая озвучка режима (в т.ч. несохранённые правки) — её сохраняем
    // и по ней же предпрослушиваем пример.
    val currentTts = TtsConfig(
        channel = ttsChannel,
        rate = ttsRate,
        pitch = ttsPitch,
        volume = ttsVolume,
        enginePackage = ttsEngine.ifBlank { null },
        voiceName = ttsVoice.ifBlank { null },
        speakToolCalls = ttsToolCalls,
        narrative = ttsNarrative,
    )
    var soundEnabled by remember { mutableStateOf(config.effectiveSoundEnabled()) }
    var autoSaveChats by remember { mutableStateOf(config.effectiveAutoSaveChats()) }
    val baseStt = config.effectiveStt()
    var sttEngine by remember { mutableStateOf(baseStt.engine) }
    var sttLanguage by remember { mutableStateOf(baseStt.language) }
    var sttRecognizerComponent by remember { mutableStateOf(baseStt.recognizerComponent) }
    var sttBluetoothInput by remember { mutableStateOf(baseStt.bluetoothInput) }
    var recognitionServices by remember { mutableStateOf(emptyList<RecognitionServiceInfo>()) }
    var engines by remember { mutableStateOf(emptyList<TtsEngineInfo>()) }
    var voices by remember { mutableStateOf(emptyList<VoiceInfo>()) }
    var engineExpanded by remember { mutableStateOf(false) }
    var localeExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }

    // Синхронизация с сохранённым конфигом: modeConfigs прилетает асинхронно
    // (сначала default с пустыми id, потом реальный), поэтому ключ — весь config,
    // а не только mode. Локальные правки config не меняют и ресинк не триггерят.
    // defaultPromptText в ключе — чтобы legacy-null (пустое поле) заполнился
    // дефолтом, когда тот догрузится; набранный пользователем текст не затираем.
    LaunchedEffect(config, defaultPromptText) {
        providerId = config.providerId ?: ""
        modelId = config.modelId ?: ""
        val stored = config.initialPrompt
        initialPrompt = when {
            stored != null -> stored
            initialPrompt.isBlank() -> defaultPromptText ?: ""
            else -> initialPrompt
        }
        autoSpeak = config.assistant?.autoSpeak ?: true
        autoClose = config.assistant?.autoClose ?: true
        attachScreenshot = config.assistant?.attachScreenshot ?: true
        continueDialog = config.assistant?.continueDialog ?: false
        showOverlay = config.assistant?.showOverlay ?: true
        sharedAction = config.share?.sharedContentAction ?: ""
        showInChat = config.share?.showInChat ?: true
        val tts = config.effectiveTts()
        ttsChannel = tts.channel
        ttsRate = tts.rate
        ttsPitch = tts.pitch
        ttsVolume = tts.volume
        ttsVoice = tts.voiceName ?: ""
        ttsEngine = tts.enginePackage ?: ""
        ttsToolCalls = tts.speakToolCalls
        ttsNarrative = tts.narrative
        soundEnabled = config.effectiveSoundEnabled()
        autoSaveChats = config.effectiveAutoSaveChats()
        val stt = config.effectiveStt()
        sttEngine = stt.engine
        sttLanguage = stt.language
        sttRecognizerComponent = stt.recognizerComponent
        sttBluetoothInput = stt.bluetoothInput
    }
    LaunchedEffect(Unit) {
        engines = runCatching { loadEngines() }.getOrElse { emptyList() }
    }
    LaunchedEffect(mode) {
        recognitionServices = loadRecognitionServices()
    }
    // Дефолтный промпт — из assets в языке приложения (см. AppGraph.defaultPromptText).
    LaunchedEffect(mode) {
        defaultPromptText = runCatching { loadDefaultPrompt() }.getOrElse { "" }
    }
    // Голоса — лениво по выбранному движку ("": привязанный/дефолт).
    // После загрузки язык подтягиваем из сохранённого голоса, иначе первый.
    LaunchedEffect(ttsEngine) {
        val loaded = runCatching { loadVoices(ttsEngine.ifBlank { null }) }.getOrElse { emptyList() }
        voices = loaded
        val savedLocale = loaded.find { it.name == ttsVoice }?.localeTag
        ttsVoiceLocale = savedLocale
            ?: loaded.firstOrNull { it.localeTag == ttsVoiceLocale }?.localeTag
            ?: loaded.firstOrNull()?.localeTag.orEmpty()
        if (ttsVoice.isNotBlank() && savedLocale == null) ttsVoice = ""
    }

    val title = when (mode) {
        AssistMode.CHAT -> stringResource(R.string.mode_chat)
        AssistMode.ASSISTANT -> stringResource(R.string.mode_assistant)
        AssistMode.SHARE -> stringResource(R.string.mode_share)
    }
    val saveLabel = stringResource(R.string.action_save)
    val backLabel = stringResource(R.string.action_back)
    val selectProviderLabel = stringResource(R.string.mode_provider)
    val selectModelLabel = stringResource(R.string.mode_model)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(OAssistDimens.ScreenContentPadding),
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .oassistHeading(),
        )

        OAssistPanel(title = title) {
            OAssistSectionHeading(text = selectProviderLabel)
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it },
            ) {
                OutlinedTextField(
                    value = providers.find { it.id == providerId }?.name ?: providerId,
                    onValueChange = { providerId = it },
                    label = { Text(selectProviderLabel) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                ) {
                    providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                providerId = p.id
                                providerExpanded = false
                                modelId = ""
                                onProviderSelected(p.id)
                            },
                        )
                    }
                }
            }

            OAssistSectionHeading(text = selectModelLabel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val modelIdLabel = stringResource(R.string.field_mode_model_id)
                OutlinedTextField(
                    value = modelId,
                    // ID модели — однострочный редактор без автокоррекции:
                    // клавиатура не должна править идентификатор.
                    onValueChange = { modelId = it },
                    label = {
                        Text(
                            text = selectModelLabel,
                            modifier = Modifier.semantics { contentDescription = modelIdLabel },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    placeholder = { Text(stringResource(R.string.mode_model_manual)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        // Перед открытием пикера подтягиваем свежий каталог выбранного провайдера.
                        onProviderSelected(providerId)
                        modelPickerOpen = true
                    },
                    modifier = Modifier.oassistTouchTarget(),
                    enabled = providerId.isNotBlank(),
                ) {
                    Text(stringResource(R.string.mode_model_pick))
                }
            }

            if (modelPickerOpen) {
                ModelPickerDialog(
                    models = models,
                    isLoading = isLoadingModels,
                    onRefresh = { onRefreshModels(providerId) },
                    onPick = {
                        modelId = it
                        modelPickerOpen = false
                    },
                    onDismiss = { modelPickerOpen = false },
                )
            }

            OAssistSectionHeading(text = stringResource(R.string.mode_caps_title))
            val currentCaps = models.find { it.id == modelId }?.capabilities ?: emptySet()
            val onState = stringResource(R.string.state_enabled)
            val offState = stringResource(R.string.state_disabled)
            ModelCapability.entries.forEach { cap ->
                val checked = cap in currentCaps
                val capEnabled = modelId.isNotBlank()
                OAssistSwitchRow(
                    checked = checked,
                    onCheckedChange = { on ->
                        if (capEnabled) {
                            onSaveCapabilities(
                                providerId,
                                modelId,
                                if (on) currentCaps + cap else currentCaps - cap,
                            )
                        }
                    },
                    label = capabilityLabel(cap),
                    stateLabelOn = onState,
                    stateLabelOff = offState,
                    enabled = capEnabled,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onProbeModel(providerId, modelId) },
                    modifier = Modifier.oassistTouchTarget(),
                    enabled = !isProbing && providerId.isNotBlank() && modelId.isNotBlank(),
                ) {
                    if (isProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(OAssistDimens.DenseSpacing),
                            strokeWidth = OAssistDimens.HairlineSpacing,
                        )
                    } else {
                        Text(stringResource(R.string.mode_probe))
                    }
                }
                Text(
                    text = stringResource(R.string.mode_probe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            OAssistSectionHeading(text = stringResource(R.string.mode_protocol_title))
            var protocolExpanded by remember { mutableStateOf(false) }
            val currentProtocol = models.find { it.id == modelId }?.protocol
            ExposedDropdownMenuBox(
                expanded = protocolExpanded,
                onExpandedChange = { protocolExpanded = it },
            ) {
                OutlinedTextField(
                    value = currentProtocol?.let { protocolName(it) } ?: stringResource(R.string.mode_protocol_auto),
                    onValueChange = {},
                    label = { Text(stringResource(R.string.mode_protocol_title)) },
                    readOnly = true,
                    enabled = modelId.isNotBlank(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                    supportingText = { Text(stringResource(R.string.mode_protocol_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
                )
                ExposedDropdownMenu(
                    expanded = protocolExpanded,
                    onDismissRequest = { protocolExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mode_protocol_auto)) },
                        onClick = {
                            if (modelId.isNotBlank()) onSaveProtocol(providerId, modelId, null)
                            protocolExpanded = false
                        },
                    )
                    ModelProtocol.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(protocolName(p)) },
                            onClick = {
                                if (modelId.isNotBlank()) onSaveProtocol(providerId, modelId, p)
                                protocolExpanded = false
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OAssistSectionHeading(
                    text = stringResource(R.string.mode_prompt),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val fresh = runCatching { loadDefaultPrompt() }.getOrElse { "" }
                            defaultPromptText = fresh
                            initialPrompt = fresh
                        }
                    },
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.mode_prompt_restore))
                }
            }
            OutlinedTextField(
                value = initialPrompt,
                onValueChange = { initialPrompt = it },
                label = { Text(stringResource(R.string.mode_prompt)) },
                placeholder = { Text(stringResource(R.string.mode_prompt_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.mode_prompt_placeholders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (mode == AssistMode.ASSISTANT) {
                OAssistSectionHeading(text = stringResource(R.string.mode_assistant_extras))
                ModeSwitch(
                    title = stringResource(R.string.mode_assistant_autospeak),
                    checked = autoSpeak,
                    onCheckedChange = { autoSpeak = it },
                    onState = onState,
                    offState = offState,
                )
                ModeSwitch(
                    title = stringResource(R.string.mode_assistant_autoclose),
                    checked = autoClose,
                    onCheckedChange = { autoClose = it },
                    onState = onState,
                    offState = offState,
                )
                ModeSwitch(
                    title = stringResource(R.string.mode_assistant_screenshot),
                    checked = attachScreenshot,
                    onCheckedChange = { attachScreenshot = it },
                    onState = onState,
                    offState = offState,
                )
                ModeSwitch(
                    title = stringResource(R.string.mode_assistant_continue_dialog),
                    checked = continueDialog,
                    onCheckedChange = { continueDialog = it },
                    onState = onState,
                    offState = offState,
                )
                ModeSwitch(
                    title = stringResource(R.string.mode_assistant_show_overlay),
                    checked = showOverlay,
                    onCheckedChange = { showOverlay = it },
                    onState = onState,
                    offState = offState,
                )
                Text(
                    text = stringResource(R.string.mode_assistant_show_overlay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mode == AssistMode.SHARE) {
                OAssistSectionHeading(text = stringResource(R.string.mode_share_extras))
                OutlinedTextField(
                    value = sharedAction,
                    onValueChange = { sharedAction = it },
                    label = { Text(stringResource(R.string.mode_share_action)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                ModeSwitch(
                    title = stringResource(R.string.mode_share_show_in_chat),
                    checked = showInChat,
                    onCheckedChange = { showInChat = it },
                    onState = onState,
                    offState = offState,
                )
            }

            TtsSection(
                channel = ttsChannel,
                onChannelChange = { ttsChannel = it },
                engines = engines,
                enginePackage = ttsEngine,
                onEngineChange = { ttsEngine = it },
                engineExpanded = engineExpanded,
                onEngineExpandedChange = { engineExpanded = it },
                voices = voices,
                voiceLocale = ttsVoiceLocale,
                onVoiceLocaleChange = { ttsVoiceLocale = it },
                localeExpanded = localeExpanded,
                onLocaleExpandedChange = { localeExpanded = it },
                voiceName = ttsVoice,
                onVoiceChange = { ttsVoice = it },
                rate = ttsRate,
                onRateChange = { ttsRate = it },
                pitch = ttsPitch,
                onPitchChange = { ttsPitch = it },
                volume = ttsVolume,
                onVolumeChange = { ttsVolume = it },
                speakToolCalls = ttsToolCalls,
                onSpeakToolCallsChange = { ttsToolCalls = it },
                narrative = ttsNarrative,
                onNarrativeChange = { ttsNarrative = it },
                voiceExpanded = voiceExpanded,
                onVoiceExpandedChange = { voiceExpanded = it },
                sampleConfig = currentTts,
                onState = onState,
                offState = offState,
            )

            SttSection(
                engine = sttEngine,
                onEngineChange = { sttEngine = it },
                language = sttLanguage,
                onLanguageChange = { sttLanguage = it },
                recognizerComponent = sttRecognizerComponent,
                onRecognizerComponentChange = { sttRecognizerComponent = it },
                recognitionServices = recognitionServices,
                onRefreshRecognitionServices = {
                    recognitionServices = loadRecognitionServices()
                },
                bluetoothInput = sttBluetoothInput,
                onBluetoothInputChange = { sttBluetoothInput = it },
                onState = onState,
                offState = offState,
            )

            OAssistSectionHeading(text = stringResource(R.string.mode_sounds_title))
            ModeSwitch(
                title = stringResource(R.string.mode_sounds_enabled),
                checked = soundEnabled,
                onCheckedChange = { soundEnabled = it },
                onState = onState,
                offState = offState,
            )
            Text(
                text = stringResource(R.string.mode_sounds_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (mode == AssistMode.CHAT || mode == AssistMode.SHARE) {
                OAssistSectionHeading(text = stringResource(R.string.mode_chats_title))
                ModeSwitch(
                    title = stringResource(R.string.mode_chats_autosave),
                    checked = autoSaveChats,
                    onCheckedChange = { autoSaveChats = it },
                    onState = onState,
                    offState = offState,
                )
                Text(
                    text = stringResource(R.string.mode_chats_autosave_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            Button(
                onClick = {
                    onSave(
                        config.copy(
                            providerId = providerId.ifBlank { null },
                            modelId = modelId.ifBlank { null },
                            // Пустое поле при сохранении заполняется промптом
                            // по умолчанию (null — только если дефолт недоступен).
                            initialPrompt = initialPrompt.ifBlank { defaultPromptText.orEmpty() }
                                .ifBlank { null },
                            assistant = if (mode == AssistMode.ASSISTANT) {
                                (config.assistant ?: AssistantExtras()).copy(
                                    autoSpeak = autoSpeak,
                                    autoClose = autoClose,
                                    attachScreenshot = attachScreenshot,
                                    continueDialog = continueDialog,
                                    showOverlay = showOverlay,
                                )
                            } else {
                                config.assistant
                            },
                            share = if (mode == AssistMode.SHARE) {
                                (config.share ?: ShareExtras()).copy(
                                    sharedContentAction = sharedAction.ifBlank { null },
                                    showInChat = showInChat,
                                )
                            } else {
                                config.share
                            },
                            tts = currentTts,
                            stt = SttConfig(
                                engine = sttEngine,
                                language = sttLanguage.ifBlank { SttConfig.DEFAULT.language },
                                recognizerComponent = sttRecognizerComponent,
                                bluetoothInput = sttBluetoothInput,
                            ),
                            soundEnabled = soundEnabled,
                            autoSaveChats = autoSaveChats,
                        ),
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .oassistTouchTarget(),
            ) {
                Text(saveLabel)
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .oassistTouchTarget(),
            ) {
                Text(backLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSection(
    channel: SpeechOutputChannel,
    onChannelChange: (SpeechOutputChannel) -> Unit,
    engines: List<TtsEngineInfo>,
    enginePackage: String,
    onEngineChange: (String) -> Unit,
    engineExpanded: Boolean,
    onEngineExpandedChange: (Boolean) -> Unit,
    voices: List<VoiceInfo>,
    voiceLocale: String,
    onVoiceLocaleChange: (String) -> Unit,
    localeExpanded: Boolean,
    onLocaleExpandedChange: (Boolean) -> Unit,
    voiceName: String,
    onVoiceChange: (String) -> Unit,
    rate: Float,
    onRateChange: (Float) -> Unit,
    pitch: Float,
    onPitchChange: (Float) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    speakToolCalls: Boolean,
    onSpeakToolCallsChange: (Boolean) -> Unit,
    narrative: Boolean,
    onNarrativeChange: (Boolean) -> Unit,
    voiceExpanded: Boolean,
    onVoiceExpandedChange: (Boolean) -> Unit,
    /** Озвучка, которую предпрослушиваем (с учётом несохранённых правок). */
    sampleConfig: TtsConfig,
    onState: String,
    offState: String,
) {
    val groupLabel = stringResource(R.string.mode_tts_channel)
    val engineLabel = stringResource(R.string.mode_tts_engine)
    val engineDefaultSuffix = stringResource(R.string.mode_tts_engine_default)
    val systemDefaultEngine = stringResource(R.string.mode_tts_engine_system)
    val languageLabel = stringResource(R.string.mode_tts_language)
    val voiceLabel = stringResource(R.string.mode_tts_voice)
    val voiceDefault = stringResource(R.string.mode_tts_voice_default)
    val networkSuffix = stringResource(R.string.mode_tts_voice_network)
    // Имена языков — платформенные (Locale API), в локали интерфейса.
    val displayLocale = remember { java.util.Locale.getDefault() }
    fun engineTitle(info: TtsEngineInfo): String =
        if (info.isDefault) "${info.label} $engineDefaultSuffix" else info.label
    val selectedEngine = engines.find { it.packageName == enginePackage }
    val languageTags = remember(voices) { voiceLanguageTags(voices) }
    fun languageTitle(tag: String): String =
        runCatching { java.util.Locale.forLanguageTag(tag).getDisplayName(displayLocale) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: tag
    val localeVoices = remember(voices, voiceLocale) {
        if (voiceLocale.isBlank()) voices else voices.filter { it.localeTag == voiceLocale }
    }
    fun voiceTitle(voice: VoiceInfo): String =
        if (voice.requiresNetwork) "${voice.name} $networkSuffix" else voice.name

    // ONLINE_TTS отложен: в выборе только живые каналы.
    val options = listOf(
        SpeechOutputChannel.OFF to stringResource(R.string.mode_tts_off),
        SpeechOutputChannel.SYSTEM_TTS to stringResource(R.string.mode_tts_system),
        SpeechOutputChannel.SCREEN_READER to stringResource(R.string.mode_tts_screen_reader),
    )
    var channelExpanded by remember { mutableStateOf(false) }

    OAssistSectionHeading(text = stringResource(R.string.mode_tts_title))

    // Канал — чистый dropdown (без ввода): значение только из списка.
    ExposedDropdownMenuBox(
        expanded = channelExpanded,
        onExpandedChange = { channelExpanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == channel }?.second ?: groupLabel,
            onValueChange = {},
            label = { Text(groupLabel) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
        )
        ExposedDropdownMenu(
            expanded = channelExpanded,
            onDismissRequest = { channelExpanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onChannelChange(value)
                        channelExpanded = false
                    },
                )
            }
        }
    }

    // Без активного скринридера анонсы канала уходят в пустоту — честный хинт.
    if (channel == SpeechOutputChannel.SCREEN_READER) {
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val readerOn = remember(channel) {
            ru.outsideproarts.oassist.core.a11y.isScreenReaderEnabled(appContext)
        }
        if (!readerOn) {
            Text(
                text = stringResource(R.string.mode_tts_screen_reader_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    ExposedDropdownMenuBox(
        expanded = engineExpanded,
        onExpandedChange = onEngineExpandedChange,
    ) {
        OutlinedTextField(
            value = selectedEngine?.let { engineTitle(it) } ?: systemDefaultEngine,
            onValueChange = {},
            label = { Text(engineLabel) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
        )
        ExposedDropdownMenu(
            expanded = engineExpanded,
            onDismissRequest = { onEngineExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(systemDefaultEngine) },
                onClick = {
                    onEngineChange("")
                    onEngineExpandedChange(false)
                },
            )
            engines.forEach { engine ->
                DropdownMenuItem(
                    text = { Text(engineTitle(engine)) },
                    onClick = {
                        onEngineChange(engine.packageName)
                        onEngineExpandedChange(false)
                    },
                )
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = localeExpanded,
        onExpandedChange = onLocaleExpandedChange,
    ) {
        OutlinedTextField(
            value = voiceLocale.takeIf { it.isNotBlank() }?.let { languageTitle(it) } ?: languageLabel,
            onValueChange = {},
            label = { Text(languageLabel) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = localeExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
        )
        ExposedDropdownMenu(
            expanded = localeExpanded,
            onDismissRequest = { onLocaleExpandedChange(false) },
        ) {
            languageTags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(languageTitle(tag)) },
                    onClick = {
                        onVoiceLocaleChange(tag)
                        onVoiceChange("")
                        onLocaleExpandedChange(false)
                    },
                )
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = voiceExpanded,
        onExpandedChange = onVoiceExpandedChange,
    ) {
        OutlinedTextField(
            value = localeVoices.find { it.name == voiceName }?.let { voiceTitle(it) }
                ?: voiceDefault,
            onValueChange = {},
            label = { Text(voiceLabel) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
        )
        ExposedDropdownMenu(
            expanded = voiceExpanded,
            onDismissRequest = { onVoiceExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(voiceDefault) },
                onClick = {
                    onVoiceChange("")
                    onVoiceExpandedChange(false)
                },
            )
            localeVoices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voiceTitle(voice)) },
                    onClick = {
                        onVoiceChange(voice.name)
                        onVoiceExpandedChange(false)
                    },
                )
            }
        }
    }

    TtsSlider(
        label = stringResource(R.string.mode_tts_rate),
        value = rate,
        range = TtsConfig.MIN_RATE..TtsConfig.MAX_RATE,
        steps = 29,
        onChange = onRateChange,
    )
    TtsSlider(
        label = stringResource(R.string.mode_tts_pitch),
        value = pitch,
        range = TtsConfig.MIN_PITCH..TtsConfig.MAX_PITCH,
        steps = 29,
        onChange = onPitchChange,
    )
    TtsSlider(
        label = stringResource(R.string.mode_tts_volume),
        value = volume,
        range = TtsConfig.MIN_VOLUME..TtsConfig.MAX_VOLUME,
        steps = 19,
        onChange = onVolumeChange,
    )

    TtsSample(config = sampleConfig)

    TtsSwitch(
        title = stringResource(R.string.mode_tts_tool_calls),
        subtitle = stringResource(R.string.mode_tts_tool_calls_hint),
        checked = speakToolCalls,
        onCheckedChange = onSpeakToolCallsChange,
        onState = onState,
        offState = offState,
    )
    TtsSwitch(
        title = stringResource(R.string.mode_tts_narrative),
        subtitle = stringResource(R.string.mode_tts_narrative_hint),
        checked = narrative,
        onCheckedChange = onNarrativeChange,
        onState = onState,
        offState = offState,
    )
}

/**
 * Предпрослушивание синтеза: применяет текущие настройки озвучки режима
 * (движок, голос, тембр, канал) и произносит тестовую фразу.
 * Канал уважается как есть: системный синтезатор — звуком, экранный чтец —
 * анонсом TalkBack (звука нет).
 */
@Composable
private fun TtsSample(config: TtsConfig) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    val phrase = stringResource(R.string.mode_tts_sample_phrase)
    val spokenText = stringResource(R.string.mode_tts_sample_spoken)
    val readerText = stringResource(R.string.mode_tts_sample_reader)
    val offText = stringResource(R.string.mode_tts_sample_off)
    val unavailableText = stringResource(R.string.mode_tts_sample_unavailable)
    val preparingText = stringResource(R.string.mode_tts_sample_preparing)

    OutlinedButton(
        onClick = {
            if (busy) return@OutlinedButton
            if (config.channel == SpeechOutputChannel.OFF) {
                status = offText
                statusIsError = true
                return@OutlinedButton
            }
            busy = true
            status = preparingText
            statusIsError = false
            scope.launch {
                val handle = SpeechHandle.get()
                val spoken = if (handle == null) {
                    false
                } else {
                    handle.applyTts(config)
                    handle.speakWhenReady(phrase)
                }
                busy = false
                statusIsError = !spoken
                status = when {
                    !spoken -> unavailableText
                    config.channel == SpeechOutputChannel.SCREEN_READER -> readerText
                    else -> spokenText
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.oassistTouchTarget(),
    ) {
        Text(stringResource(R.string.mode_tts_sample))
    }
    OAssistLiveStatus(
        text = status,
        horizontalPadding = 0.dp,
        assertive = statusIsError,
    )
}

@Composable
private fun TtsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val valueText = "%.2f".format(value)
    val stepIncrement = (range.endInclusive - range.start) / 20f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Лейбл и ползунок — один узел TalkBack (ContentGrouping).
            // Иначе между ползунком и следующим элементом остаётся отдельный
            // «текстовый лейбл» — лишний свайп в навигации.
            .oassistMergedContent(),
    ) {
        Text(
            text = "$label: $valueText",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .oassistSlider(
                    label = label,
                    valueText = valueText,
                    range = range,
                    onValueChange = onChange,
                    stepIncrement = stepIncrement,
                    currentValue = { value },
                ),
        )
    }
}

@Composable
private fun TtsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onState: String,
    offState: String,
) {
    val state = if (checked) onState else offState
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .oassistTouchTarget()
            .oassistSwitchRow(checked = checked, onValueChange = onCheckedChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttSection(
    engine: SttEngine,
    onEngineChange: (SttEngine) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    recognizerComponent: String?,
    onRecognizerComponentChange: (String?) -> Unit,
    recognitionServices: List<RecognitionServiceInfo>,
    onRefreshRecognitionServices: () -> Unit,
    bluetoothInput: Boolean,
    onBluetoothInputChange: (Boolean) -> Unit,
    onState: String,
    offState: String,
) {
    val groupLabel = stringResource(R.string.mode_stt_title)
    val context = LocalContext.current
    val voice = remember { VoiceHandle.get() }
    var btPermissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // BLUETOOTH_CONNECT запрашивается один раз; отказ не превращается в повторные вопросы.
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> btPermissionGranted = granted }
    val options = listOf(
        SttEngine.SYSTEM to stringResource(R.string.mode_stt_system),
        SttEngine.MODEL_AUDIO to stringResource(R.string.mode_stt_model_audio),
    )
    var expanded by remember { mutableStateOf(false) }

    OAssistSectionHeading(text = groupLabel)

    // Движок — чистый dropdown (без ввода): значение только из списка.
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == engine }?.second ?: groupLabel,
            onValueChange = {},
            label = { Text(groupLabel) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onEngineChange(value)
                        expanded = false
                    },
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.mode_stt_model_audio_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val recognizerLabel = stringResource(R.string.mode_stt_recognizer)
    val defaultRecognizerLabel = stringResource(R.string.mode_stt_recognizer_default)
    val missingRecognizerLabel = stringResource(R.string.mode_stt_recognizer_missing)
    val unavailableRecognizerLabel = stringResource(R.string.mode_stt_recognizer_unavailable)
    val selectedRecognizer = recognitionServices.firstOrNull {
        it.componentName == recognizerComponent
    }
    val selectedRecognizerLabel = when {
        recognizerComponent == null -> defaultRecognizerLabel
        selectedRecognizer != null -> selectedRecognizer.label
        else -> missingRecognizerLabel
    }
    var recognizerExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = recognizerExpanded,
        onExpandedChange = { recognizerExpanded = it },
    ) {
        OutlinedTextField(
            value = selectedRecognizerLabel,
            onValueChange = {},
            label = { Text(recognizerLabel) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recognizerExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .oassistDropdownClassName()
                .oassistTouchTarget(),
        )
        ExposedDropdownMenu(
            expanded = recognizerExpanded,
            onDismissRequest = { recognizerExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(defaultRecognizerLabel) },
                onClick = {
                    onRecognizerComponentChange(null)
                    recognizerExpanded = false
                },
                modifier = Modifier.oassistTouchTarget(),
            )
            recognitionServices.forEach { service ->
                DropdownMenuItem(
                    text = { Text(service.label) },
                    onClick = {
                        onRecognizerComponentChange(service.componentName)
                        recognizerExpanded = false
                    },
                    modifier = Modifier.oassistTouchTarget(),
                )
            }
        }
    }
    if (recognizerComponent != null && selectedRecognizer == null) {
        OAssistLiveStatus(text = unavailableRecognizerLabel)
    }
    TextButton(
        onClick = onRefreshRecognitionServices,
        modifier = Modifier.oassistTouchTarget(),
    ) {
        Text(stringResource(R.string.mode_stt_recognizer_refresh))
    }
    val sttLangLabel = stringResource(R.string.field_mode_stt_lang)
    OutlinedTextField(
        value = language,
        onValueChange = onLanguageChange,
        label = {
            Text(
                text = stringResource(R.string.mode_stt_language),
                modifier = Modifier.semantics { contentDescription = sttLangLabel },
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    ModeSwitch(
        title = stringResource(R.string.mode_stt_bluetooth),
        checked = bluetoothInput,
        onCheckedChange = { checked ->
            onBluetoothInputChange(checked)
            if (checked &&
                voice?.bluetoothInputDecision(true) == BtInputDecision.ASK_PERMISSION
            ) {
                voice.markBluetoothPermissionRequested()
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        },
        onState = onState,
        offState = offState,
    )
    Text(
        text = stringResource(R.string.mode_stt_bluetooth_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (bluetoothInput && !btPermissionGranted) {
        OAssistLiveStatus(text = stringResource(R.string.mode_stt_bluetooth_no_permission))
    }
}

@Composable
private fun ModeSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onState: String,
    offState: String,
) {
    val state = if (checked) onState else offState
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .oassistTouchTarget()
            .oassistSwitchRow(checked = checked, onValueChange = onCheckedChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

@Composable
private fun capabilityLabel(cap: ModelCapability): String = when (cap) {
    ModelCapability.TOOLS -> stringResource(R.string.cap_tools)
    ModelCapability.VISION -> stringResource(R.string.cap_vision)
    ModelCapability.AUDIO_IN -> stringResource(R.string.cap_audio_in)
    ModelCapability.REASONING -> stringResource(R.string.cap_reasoning)
}

@Composable
private fun protocolName(protocol: ModelProtocol): String = when (protocol) {
    ModelProtocol.OPENAI_COMPLETIONS -> stringResource(R.string.protocol_openai_completions)
    ModelProtocol.OPENAI_RESPONSES -> stringResource(R.string.protocol_openai_responses)
    ModelProtocol.ANTHROPIC_MESSAGES -> stringResource(R.string.protocol_anthropic_messages)
    ModelProtocol.GEMINI_GENERATE -> stringResource(R.string.protocol_gemini_generate)
}

/**
 * Пикер моделей: диалог со списком от провайдера. Тап по модели заполняет
 * поле ID модели выбранной. Список обновляется кнопкой «Обновить».
 */
@Composable
private fun ModelPickerDialog(
    models: List<ModelInfo>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_model_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(OAssistDimens.DenseSpacing),
                            strokeWidth = OAssistDimens.HairlineSpacing,
                        )
                    } else {
                        Text(stringResource(R.string.mode_model_refresh))
                    }
                }
                if (!isLoading && models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mode_model_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                models.forEach { m ->
                    val itemLabel = stringResource(
                        R.string.mode_model_pick_item,
                        m.label?.takeIf { it.isNotBlank() } ?: m.id,
                    )
                    TextButton(
                        onClick = { onPick(m.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .oassistTouchTarget()
                            .semantics { contentDescription = itemLabel },
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = m.id,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val protocolOverride = m.protocol
                            if (protocolOverride != null) {
                                Text(
                                    text = stringResource(R.string.mode_protocol_badge, protocolName(protocolOverride)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Text(stringResource(R.string.action_back))
            }
        },
    )
}
