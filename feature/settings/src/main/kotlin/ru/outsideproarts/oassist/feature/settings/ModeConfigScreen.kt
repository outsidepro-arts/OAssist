package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.AssistantExtras
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ShareExtras
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.SpeechOutputChannel
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.model.SttEngine
import ru.outsideproarts.oassist.core.model.TtsConfig
import ru.outsideproarts.oassist.core.speech.VoiceInfo
import ru.outsideproarts.oassist.core.speech.TtsEngineInfo
import ru.outsideproarts.oassist.core.speech.voiceLanguageTags
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Установленные синтезаторы; пусто — список недоступен (только дефолт). */
    loadEngines: suspend () -> List<TtsEngineInfo> = { emptyList() },
    /** Голоса движка (`null` — привязанный/дефолт); пусто — только голос по умолчанию. */
    loadVoices: suspend (enginePackage: String?) -> List<VoiceInfo> = { emptyList() },
) {
    var providerId by remember { mutableStateOf(config.providerId ?: "") }
    var modelId by remember { mutableStateOf(config.modelId ?: "") }
    var initialPrompt by remember { mutableStateOf(config.initialPrompt ?: "") }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var autoSpeak by remember { mutableStateOf(config.assistant?.autoSpeak ?: true) }
    var autoClose by remember { mutableStateOf(config.assistant?.autoClose ?: true) }
    var attachScreenshot by remember { mutableStateOf(config.assistant?.attachScreenshot ?: true) }
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
    val baseStt = config.effectiveStt()
    var sttEngine by remember { mutableStateOf(baseStt.engine) }
    var sttLanguage by remember { mutableStateOf(baseStt.language) }
    var engines by remember { mutableStateOf(emptyList<TtsEngineInfo>()) }
    var voices by remember { mutableStateOf(emptyList<VoiceInfo>()) }
    var engineExpanded by remember { mutableStateOf(false) }
    var localeExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }

    // Синхронизация с сохранённым конфигом: modeConfigs прилетает асинхронно
    // (сначала default с пустыми id, потом реальный), поэтому ключ — весь config,
    // а не только mode. Локальные правки config не меняют и ресинк не триггерят.
    LaunchedEffect(config) {
        providerId = config.providerId ?: ""
        modelId = config.modelId ?: ""
        initialPrompt = config.initialPrompt ?: ""
        autoSpeak = config.assistant?.autoSpeak ?: true
        autoClose = config.assistant?.autoClose ?: true
        attachScreenshot = config.assistant?.attachScreenshot ?: true
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
        val stt = config.effectiveStt()
        sttEngine = stt.engine
        sttLanguage = stt.language
    }
    LaunchedEffect(Unit) {
        engines = runCatching { loadEngines() }.getOrElse { emptyList() }
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
    val manualInputLabel = stringResource(R.string.mode_model_manual)

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
                        .menuAnchor(),
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
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text(selectModelLabel) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        if (models.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.mode_model_empty))
                                },
                                enabled = false,
                                onClick = { modelExpanded = false },
                            )
                        }
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.id) },
                                onClick = {
                                    modelId = m.id
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onRefreshModels(providerId) },
                    modifier = Modifier.oassistTouchTarget(),
                    enabled = !isLoadingModels && providerId.isNotBlank(),
                ) {
                    if (isLoadingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(OAssistDimens.DenseSpacing),
                            strokeWidth = OAssistDimens.HairlineSpacing,
                        )
                    } else {
                        Text(stringResource(R.string.mode_model_refresh))
                    }
                }
            }

            Text(
                text = manualInputLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OAssistSectionHeading(text = stringResource(R.string.mode_caps_title))
            val currentCaps = models.find { it.id == modelId }?.capabilities ?: emptySet()
            val onState = stringResource(R.string.state_enabled)
            val offState = stringResource(R.string.state_disabled)
            ModelCapability.entries.forEach { cap ->
                val checked = cap in currentCaps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
                ) {
                    Text(
                        text = capabilityLabel(cap),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = { on ->
                            if (modelId.isNotBlank()) {
                                onSaveCapabilities(
                                    providerId,
                                    modelId,
                                    if (on) currentCaps + cap else currentCaps - cap,
                                )
                            }
                        },
                        enabled = modelId.isNotBlank(),
                        modifier = Modifier
                            .oassistTouchTarget()
                            .semantics { stateDescription = if (checked) onState else offState },
                    )
                }
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

            OAssistSectionHeading(text = stringResource(R.string.mode_prompt))
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
                onState = onState,
                offState = offState,
            )

            SttSection(
                engine = sttEngine,
                onEngineChange = { sttEngine = it },
                language = sttLanguage,
                onLanguageChange = { sttLanguage = it },
            )
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
                            initialPrompt = initialPrompt.ifBlank { null },
                            assistant = if (mode == AssistMode.ASSISTANT) {
                                (config.assistant ?: AssistantExtras()).copy(
                                    autoSpeak = autoSpeak,
                                    autoClose = autoClose,
                                    attachScreenshot = attachScreenshot,
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
                            tts = TtsConfig(
                                channel = ttsChannel,
                                rate = ttsRate,
                                pitch = ttsPitch,
                                volume = ttsVolume,
                                enginePackage = ttsEngine.ifBlank { null },
                                voiceName = ttsVoice.ifBlank { null },
                                speakToolCalls = ttsToolCalls,
                                narrative = ttsNarrative,
                            ),
                            stt = SttConfig(
                                engine = sttEngine,
                                language = sttLanguage.ifBlank { SttConfig.DEFAULT.language },
                            ),
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

    OAssistSectionHeading(text = stringResource(R.string.mode_tts_title))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { paneTitle = groupLabel },
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        options.forEach { (value, label) ->
            val selected = channel == value
            val state = if (selected) onState else offState
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .oassistTouchTarget()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onChannelChange(value) },
                    )
                    .semantics { stateDescription = state }
                    .padding(horizontal = OAssistDimens.DenseSpacing, vertical = OAssistDimens.ItemSpacing),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.OptionRowSpacing),
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.oassistRole(Role.RadioButton),
                )
            }
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
                .menuAnchor(),
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
                .menuAnchor(),
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
                .menuAnchor(),
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

@Composable
private fun TtsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ${"%.2f".format(value)}",
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
                .oassistTouchTarget(),
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
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .oassistTouchTarget()
                .semantics { stateDescription = if (checked) onState else offState },
        )
    }
}

@Composable
private fun SttSection(
    engine: SttEngine,
    onEngineChange: (SttEngine) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    val groupLabel = stringResource(R.string.mode_stt_title)
    val options = listOf(
        SttEngine.SYSTEM to stringResource(R.string.mode_stt_system),
        SttEngine.MODEL_AUDIO to stringResource(R.string.mode_stt_model_audio),
    )

    OAssistSectionHeading(text = groupLabel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { paneTitle = groupLabel },
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .oassistTouchTarget()
                    .selectable(
                        selected = engine == value,
                        role = Role.RadioButton,
                        onClick = { onEngineChange(value) },
                    )
                    .padding(horizontal = OAssistDimens.DenseSpacing, vertical = OAssistDimens.ItemSpacing),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.OptionRowSpacing),
            ) {
                RadioButton(selected = engine == value, onClick = null)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.oassistRole(Role.RadioButton),
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.mode_stt_model_audio_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = language,
        onValueChange = onLanguageChange,
        label = { Text(stringResource(R.string.mode_stt_language)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModeSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onState: String,
    offState: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .oassistTouchTarget()
                .semantics { stateDescription = if (checked) onState else offState },
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
