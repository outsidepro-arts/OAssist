package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import ru.outsideproarts.oassist.core.a11y.oassistDropdownClassName
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.AuthScheme
import ru.outsideproarts.oassist.core.model.GoogleApiVersion
import ru.outsideproarts.oassist.core.model.ModelFetchScheme
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.OpenCodeEndpoint
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.isValidExtraBody
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSwitchRow
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading

/**
 * Секреты из полей ввода при сохранении. Null — значение не менялось;
 * [clearApiKey]/[clearBasic] — явное удаление сохранённого; [clearedExtraHeaders] —
 * удалённые дополнительные заголовки.
 */
data class ProviderSecretsInput(
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
    val basicUser: String? = null,
    val basicPassword: String? = null,
    val clearBasic: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    val clearedExtraHeaders: List<String> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    profile: ProviderProfile?,
    isSaving: Boolean,
    saveError: String?,
    onSave: (ProviderProfile, ProviderSecretsInput, String?, (String?, Boolean) -> Unit) -> Unit,
    onDismissSaveError: () -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    sourceProviderId: String? = profile?.id,
) {
    var draftProviderId by rememberSaveable { mutableStateOf(sourceProviderId) }
    val isNew = draftProviderId == null
    // Пользовательский ввод переживает пересоздание Activity (поворот, смерть
    // процесса): rememberSaveable. Секреты ввода намеренно остаются в remember —
    // открытые значения не пишутся в savedInstanceState.
    var name by rememberSaveable { mutableStateOf(profile?.name ?: "") }
    var draftRevision by rememberSaveable { mutableStateOf(profile?.credentialRevision) }
    var preset by rememberSaveable { mutableStateOf(profile?.preset ?: ProviderPreset.OPENAI) }
    var baseUrl by rememberSaveable { mutableStateOf(profile?.baseUrl ?: "") }
    var protocol by rememberSaveable(stateSaver = modelProtocolSaver) { mutableStateOf(profile?.protocol) }
    var fetchScheme by rememberSaveable { mutableStateOf(profile?.fetchScheme ?: ModelFetchScheme.MODELS) }
    var authScheme by rememberSaveable { mutableStateOf(profile?.authScheme ?: AuthScheme.NATIVE) }
    var apiKeyHeaderName by rememberSaveable { mutableStateOf(profile?.apiKeyHeaderName ?: "") }
    var catalogPath by rememberSaveable { mutableStateOf(profile?.catalogPath ?: "") }
    var extraBody by rememberSaveable { mutableStateOf(profile?.extraBody ?: "") }
    var extraBodyInvalid by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(profile?.enabled ?: true) }
    // Секреты ввода: только в памяти, при смерти процесса поле пустеет.
    var apiKeyInput by remember { mutableStateOf("") }
    var hasSavedKey by rememberSaveable { mutableStateOf(profile?.apiKeyRef != null) }
    var clearApiKey by rememberSaveable { mutableStateOf(false) }
    var basicUserInput by remember { mutableStateOf("") }
    var basicPasswordInput by remember { mutableStateOf("") }
    var hasSavedBasicUser by rememberSaveable { mutableStateOf(profile?.basicUserRef != null) }
    var hasSavedBasicPassword by rememberSaveable { mutableStateOf(profile?.basicPasswordRef != null) }
    var clearBasic by rememberSaveable { mutableStateOf(false) }
    // Показ секретов ввода: по умолчанию маскирован.
    var apiKeyVisible by remember { mutableStateOf(false) }
    var basicPasswordVisible by remember { mutableStateOf(false) }
    var newHeaderValueVisible by remember { mutableStateOf(false) }
    // Сохранённые и новые дополнительные заголовки («Своя настройка»).
    // savedHeaders: имя заголовка → alias в SecretStore (не секрет) — сохраняем;
    // pendingHeaders: имя → открытое значение (секрет) — только в памяти.
    var savedHeaders by rememberSaveable { mutableStateOf(profile?.extraHeaders.orEmpty()) }
    var clearedHeaders by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingHeaders by remember { mutableStateOf(mapOf<String, String>()) }
    var newHeaderName by rememberSaveable { mutableStateOf("") }
    var newHeaderValue by remember { mutableStateOf("") }
    // Выборы пресетов: под капотом задают скрытый baseUrl.
    var googleVersion by rememberSaveable {
        mutableStateOf(GoogleApiVersion.fromBaseUrl(profile?.baseUrl ?: ProviderPreset.GOOGLE.defaultBaseUrl().orEmpty()))
    }
    var openCodeEndpoint by rememberSaveable {
        mutableStateOf(OpenCodeEndpoint.fromBaseUrl(profile?.baseUrl ?: ProviderPreset.OPENCODE.defaultBaseUrl().orEmpty()))
    }
    // Dropdown-селекторы — чистые dropdown (без ввода): значение только из списка.
    var presetExpanded by remember { mutableStateOf(false) }
    var protocolExpanded by remember { mutableStateOf(false) }
    var fetchExpanded by remember { mutableStateOf(false) }
    var authExpanded by remember { mutableStateOf(false) }
    var googleVersionExpanded by remember { mutableStateOf(false) }
    var openCodeEndpointExpanded by remember { mutableStateOf(false) }

    // Заполнение формы из профиля — только при реальной смене источника.
    // syncedProfileId переживает пересоздание: повторный вход в композицию
    // (поворот, смерть процесса) не затирает восстановленный ввод. Пустая
    // строка — «из профиля ещё не заполнялись» (новый провайдер — никогда).
    var syncedProfileId by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(profile?.id) {
        val current = profile ?: return@LaunchedEffect
        if (current.id == syncedProfileId) return@LaunchedEffect
        syncedProfileId = current.id
        draftProviderId = current.id
        draftRevision = current.credentialRevision
        name = current.name
        preset = current.preset
        baseUrl = current.baseUrl
        protocol = current.protocol
        fetchScheme = current.fetchScheme
        authScheme = current.authScheme
        apiKeyHeaderName = current.apiKeyHeaderName ?: ""
        catalogPath = current.catalogPath ?: ""
        extraBody = current.extraBody ?: ""
        enabled = current.enabled
        hasSavedKey = current.apiKeyRef != null
        hasSavedBasicUser = current.basicUserRef != null
        hasSavedBasicPassword = current.basicPasswordRef != null
        savedHeaders = current.extraHeaders
        clearedHeaders = emptyList()
        pendingHeaders = emptyMap()
        clearApiKey = false
        clearBasic = false
        apiKeyInput = ""
        basicUserInput = ""
        basicPasswordInput = ""
        googleVersion = GoogleApiVersion.fromBaseUrl(current.baseUrl)
        openCodeEndpoint = OpenCodeEndpoint.fromBaseUrl(current.baseUrl)
    }

    fun applyPreset(newPreset: ProviderPreset) {
        if (newPreset != ProviderPreset.CUSTOM) {
            // Рецепт пресета: учётные данные и имя не трогаем.
            protocol = newPreset.defaultProtocol()
            fetchScheme = newPreset.defaultFetchScheme()
            authScheme = newPreset.defaultAuthScheme()
            apiKeyHeaderName = ""
            catalogPath = ""
            extraBody = ""
            clearedHeaders = savedHeaders.keys.toList() + clearedHeaders
            savedHeaders = emptyMap()
            pendingHeaders = emptyMap()
            newPreset.defaultBaseUrl()?.let { baseUrl = it }
            if (newPreset == ProviderPreset.GOOGLE) googleVersion = GoogleApiVersion.BETA
            if (newPreset == ProviderPreset.OPENCODE) openCodeEndpoint = OpenCodeEndpoint.ZEN
        }
        // «Своя настройка» ничего не перезаписывает: показывает спрятанные поля.
        preset = newPreset
    }

    fun buildProfile(): ProviderProfile =
        (profile ?: ProviderProfile(id = draftProviderId ?: generateId(), name = "", preset = preset, baseUrl = "")).copy(
            name = name,
            credentialRevision = draftRevision,
            preset = preset,
            baseUrl = baseUrl,
            protocol = protocol,
            fetchScheme = fetchScheme,
            authScheme = authScheme,
            apiKeyHeaderName = apiKeyHeaderName.trim().ifBlank { null },
            catalogPath = catalogPath.trim().ifBlank { null },
            extraBody = extraBody.trim().ifBlank { null },
            extraHeaders = savedHeaders,
            enabled = enabled,
        )

    fun buildSecrets(): ProviderSecretsInput = ProviderSecretsInput(
        apiKey = apiKeyInput.ifBlank { null },
        clearApiKey = clearApiKey,
        basicUser = basicUserInput.ifBlank { null },
        basicPassword = basicPasswordInput.ifBlank { null },
        clearBasic = clearBasic,
        extraHeaders = pendingHeaders,
        clearedExtraHeaders = clearedHeaders,
    )

    val title = if (isNew) {
        stringResource(R.string.provider_edit_add_title)
    } else {
        stringResource(R.string.provider_edit_title)
    }
    val saveLabel = stringResource(R.string.action_save)
    val deleteLabel = stringResource(R.string.action_delete)
    val backLabel = stringResource(R.string.action_back)
    val replaceLabel = stringResource(R.string.action_replace)
    val enabledLabel = stringResource(R.string.provider_enabled)
    val enabledState = stringResource(R.string.state_enabled)
    val disabledState = stringResource(R.string.state_disabled)
    val maskedLabel = stringResource(R.string.provider_api_key_masked)
    val showPasswordLabel = stringResource(R.string.password_show)
    val hidePasswordLabel = stringResource(R.string.password_hide)
    val validatingLabel = stringResource(R.string.provider_validating)

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
            // Служебные поля — каждый своим видом: имя — однострочный редактор
            // без автокоррекции, URL — URL-клавиатура, секреты — пароли.
            val nameLabel = stringResource(R.string.field_provider_name)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = {
                    Text(
                        text = stringResource(R.string.provider_name),
                        modifier = Modifier.semantics { contentDescription = nameLabel },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )

            OAssistSectionHeading(text = stringResource(R.string.provider_kind))
            ExposedDropdownMenuBox(
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = it },
            ) {
                OutlinedTextField(
                    value = presetLabel(preset),
                    onValueChange = {},
                    label = { Text(stringResource(R.string.provider_kind)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                    supportingText = { Text(presetDescription(preset)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
                )
                ExposedDropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false },
                ) {
                    ProviderPreset.entries.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(presetLabel(p))
                                    Text(
                                        text = presetDescription(p),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                applyPreset(p)
                                presetExpanded = false
                            },
                        )
                    }
                }
            }

            // Выборы пресетов задают скрытый Base URL под капотом.
            if (preset == ProviderPreset.GOOGLE) {
                OAssistSectionHeading(text = stringResource(R.string.provider_google_version_title))
                ExposedDropdownMenuBox(
                    expanded = googleVersionExpanded,
                    onExpandedChange = { googleVersionExpanded = it },
                ) {
                    OutlinedTextField(
                        value = googleVersionLabel(googleVersion),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.provider_google_version_title)) },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = googleVersionExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .oassistDropdownClassName(),
                    )
                    ExposedDropdownMenu(
                        expanded = googleVersionExpanded,
                        onDismissRequest = { googleVersionExpanded = false },
                    ) {
                        GoogleApiVersion.entries.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(googleVersionLabel(v)) },
                                onClick = {
                                    googleVersion = v
                                    baseUrl = v.baseUrl
                                    googleVersionExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            if (preset == ProviderPreset.OPENCODE) {
                OAssistSectionHeading(text = stringResource(R.string.provider_opencode_endpoint_title))
                ExposedDropdownMenuBox(
                    expanded = openCodeEndpointExpanded,
                    onExpandedChange = { openCodeEndpointExpanded = it },
                ) {
                    OutlinedTextField(
                        value = openCodeEndpointLabel(openCodeEndpoint),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.provider_opencode_endpoint_title)) },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = openCodeEndpointExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .oassistDropdownClassName(),
                    )
                    ExposedDropdownMenu(
                        expanded = openCodeEndpointExpanded,
                        onDismissRequest = { openCodeEndpointExpanded = false },
                    ) {
                        OpenCodeEndpoint.entries.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(openCodeEndpointLabel(e)) },
                                onClick = {
                                    openCodeEndpoint = e
                                    baseUrl = e.baseUrl
                                    openCodeEndpointExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            if (preset == ProviderPreset.CUSTOM) {
                val baseUrlLabel = stringResource(R.string.field_provider_url)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = {
                        Text(
                            text = stringResource(R.string.provider_base_url),
                            modifier = Modifier.semantics { contentDescription = baseUrlLabel },
                        )
                    },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.provider_base_url_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OAssistSectionHeading(text = stringResource(R.string.provider_protocol_title))
                ExposedDropdownMenuBox(
                    expanded = protocolExpanded,
                    onExpandedChange = { protocolExpanded = it },
                ) {
                    OutlinedTextField(
                        value = protocol?.let { protocolLabel(it) }
                            ?: stringResource(R.string.provider_protocol_auto),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.provider_protocol_title)) },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                        supportingText = { Text(stringResource(R.string.provider_protocol_hint)) },
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
                            text = { Text(stringResource(R.string.provider_protocol_auto)) },
                            onClick = {
                                protocol = null
                                protocolExpanded = false
                            },
                        )
                        ModelProtocol.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(protocolLabel(p)) },
                                onClick = {
                                    protocol = p
                                    protocolExpanded = false
                                },
                            )
                        }
                    }
                }

                OAssistSectionHeading(text = stringResource(R.string.provider_fetch_scheme_title))
                ExposedDropdownMenuBox(
                    expanded = fetchExpanded,
                    onExpandedChange = { fetchExpanded = it },
                ) {
                    OutlinedTextField(
                        value = fetchSchemeLabel(fetchScheme),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.provider_fetch_scheme_title)) },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fetchExpanded) },
                        supportingText = { Text(stringResource(R.string.provider_fetch_scheme_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .oassistDropdownClassName(),
                    )
                    ExposedDropdownMenu(
                        expanded = fetchExpanded,
                        onDismissRequest = { fetchExpanded = false },
                    ) {
                        ModelFetchScheme.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(fetchSchemeLabel(s)) },
                                onClick = {
                                    fetchScheme = s
                                    fetchExpanded = false
                                },
                            )
                        }
                    }
                }
                val catalogPathLabel = stringResource(R.string.field_provider_catalog_path)
                OutlinedTextField(
                    value = catalogPath,
                    onValueChange = { catalogPath = it },
                    label = {
                        Text(
                            text = stringResource(R.string.provider_catalog_path),
                            modifier = Modifier.semantics { contentDescription = catalogPathLabel },
                        )
                    },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.provider_catalog_path_hint)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )

                OAssistSectionHeading(text = stringResource(R.string.provider_auth_scheme_title))
                ExposedDropdownMenuBox(
                    expanded = authExpanded,
                    onExpandedChange = { authExpanded = it },
                ) {
                    OutlinedTextField(
                        value = authSchemeLabel(authScheme),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.provider_auth_scheme_title)) },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authExpanded) },
                        supportingText = { Text(stringResource(R.string.provider_auth_scheme_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .oassistDropdownClassName(),
                    )
                    ExposedDropdownMenu(
                        expanded = authExpanded,
                        onDismissRequest = { authExpanded = false },
                    ) {
                        AuthScheme.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(authSchemeLabel(s)) },
                                onClick = {
                                    authScheme = s
                                    authExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Поля учётных данных — по схеме авторизации: у пресетов она нативная
            // (один API-ключ), в «Своей настройке» зависит от выбранной схемы.
            when (authScheme) {
                AuthScheme.NONE -> {}
                AuthScheme.BASIC -> {
                    val userLabel = stringResource(R.string.field_provider_basic_user)
                    OutlinedTextField(
                        value = if (hasSavedBasicUser) "" else basicUserInput,
                        onValueChange = {
                            basicUserInput = it
                            if (it.isNotEmpty()) clearBasic = false
                        },
                        label = {
                            Text(
                                text = if (hasSavedBasicUser) {
                                    maskedLabel
                                } else {
                                    stringResource(R.string.provider_basic_user)
                                },
                                modifier = Modifier.semantics { contentDescription = userLabel },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val passwordLabel = stringResource(R.string.field_provider_basic_password)
                    OutlinedTextField(
                        value = if (hasSavedBasicPassword) "" else basicPasswordInput,
                        onValueChange = {
                            basicPasswordInput = it
                            if (it.isNotEmpty()) clearBasic = false
                        },
                        label = {
                            Text(
                                text = if (hasSavedBasicPassword) {
                                    maskedLabel
                                } else {
                                    stringResource(R.string.provider_basic_password)
                                },
                                modifier = Modifier.semantics { contentDescription = passwordLabel },
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (basicPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { basicPasswordVisible = !basicPasswordVisible }) {
                                Icon(
                                    imageVector = if (basicPasswordVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (basicPasswordVisible) {
                                        hidePasswordLabel
                                    } else {
                                        showPasswordLabel
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if ((hasSavedBasicUser || hasSavedBasicPassword) && !clearBasic) {
                        Row(horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing)) {
                            OutlinedButton(
                                onClick = {
                                    hasSavedBasicUser = false
                                    hasSavedBasicPassword = false
                                    basicUserInput = ""
                                    basicPasswordInput = ""
                                },
                                modifier = Modifier.oassistTouchTarget(),
                            ) {
                                Text(replaceLabel)
                            }
                            OutlinedButton(
                                onClick = {
                                    hasSavedBasicUser = false
                                    hasSavedBasicPassword = false
                                    clearBasic = true
                                    basicUserInput = ""
                                    basicPasswordInput = ""
                                },
                                modifier = Modifier.oassistTouchTarget(),
                            ) {
                                Text(deleteLabel)
                            }
                        }
                    }
                }
                else -> {
                    if (authScheme == AuthScheme.API_KEY_HEADER) {
                        val headerNameLabel = stringResource(R.string.field_provider_key_header)
                        OutlinedTextField(
                            value = apiKeyHeaderName,
                            onValueChange = { apiKeyHeaderName = it },
                            label = {
                                Text(
                                    text = stringResource(R.string.provider_api_key_header_name),
                                    modifier = Modifier.semantics { contentDescription = headerNameLabel },
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val apiKeyLabel = stringResource(R.string.field_provider_key)
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            if (it.isNotEmpty()) clearApiKey = false
                        },
                        label = {
                            Text(
                                text = if (hasSavedKey) {
                                    maskedLabel
                                } else {
                                    stringResource(R.string.provider_api_key)
                                },
                                modifier = Modifier.semantics { contentDescription = apiKeyLabel },
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (apiKeyVisible) {
                                        hidePasswordLabel
                                    } else {
                                        showPasswordLabel
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (hasSavedKey && !clearApiKey) {
                        Row(horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing)) {
                            OutlinedButton(
                                onClick = {
                                    hasSavedKey = false
                                    apiKeyInput = ""
                                },
                                modifier = Modifier.oassistTouchTarget(),
                            ) {
                                Text(replaceLabel)
                            }
                            OutlinedButton(
                                onClick = {
                                    hasSavedKey = false
                                    clearApiKey = true
                                    apiKeyInput = ""
                                },
                                modifier = Modifier.oassistTouchTarget(),
                            ) {
                                Text(deleteLabel)
                            }
                        }
                    }
                }
            }

            if (preset == ProviderPreset.CUSTOM) {
                val extraBodyLabel = stringResource(R.string.field_provider_extra_body)
                OutlinedTextField(
                    value = extraBody,
                    onValueChange = {
                        extraBody = it
                        extraBodyInvalid = false
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.provider_extra_body),
                            modifier = Modifier.semantics { contentDescription = extraBodyLabel },
                        )
                    },
                    isError = extraBodyInvalid,
                    supportingText = {
                        Text(
                            text = if (extraBodyInvalid) {
                                stringResource(R.string.provider_extra_body_error)
                            } else {
                                stringResource(R.string.provider_extra_body_hint)
                            },
                        )
                    },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )

                OAssistSectionHeading(text = stringResource(R.string.provider_extra_headers))
                (savedHeaders.keys + pendingHeaders.keys).forEach { headerName ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$headerName · $maskedLabel",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                savedHeaders = savedHeaders - headerName
                                pendingHeaders = pendingHeaders - headerName
                                if (!clearedHeaders.contains(headerName)) {
                                    clearedHeaders = clearedHeaders + headerName
                                }
                            },
                            modifier = Modifier.oassistTouchTarget(),
                        ) {
                            Text(deleteLabel)
                        }
                    }
                }
                val newHeaderNameLabel = stringResource(R.string.field_provider_header_name)
                OutlinedTextField(
                    value = newHeaderName,
                    onValueChange = { newHeaderName = it },
                    label = {
                        Text(
                            text = stringResource(R.string.provider_header_name),
                            modifier = Modifier.semantics { contentDescription = newHeaderNameLabel },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                val newHeaderValueLabel = stringResource(R.string.field_provider_header_value)
                OutlinedTextField(
                    value = newHeaderValue,
                    onValueChange = { newHeaderValue = it },
                    label = {
                        Text(
                            text = stringResource(R.string.provider_header_value),
                            modifier = Modifier.semantics { contentDescription = newHeaderValueLabel },
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (newHeaderValueVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { newHeaderValueVisible = !newHeaderValueVisible }) {
                            Icon(
                                imageVector = if (newHeaderValueVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (newHeaderValueVisible) {
                                    hidePasswordLabel
                                } else {
                                    showPasswordLabel
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        val headerName = newHeaderName.trim()
                        if (headerName.isNotEmpty() && newHeaderValue.isNotEmpty()) {
                            pendingHeaders = pendingHeaders + (headerName to newHeaderValue)
                            newHeaderName = ""
                            newHeaderValue = ""
                        }
                    },
                    enabled = newHeaderName.isNotBlank() && newHeaderValue.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.provider_header_add))
                }
            }

            OAssistSwitchRow(
                checked = enabled,
                onCheckedChange = { enabled = it },
                label = enabledLabel,
                stateLabelOn = enabledState,
                stateLabelOff = disabledState,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            Button(
                onClick = {
                    if (!isValidExtraBody(extraBody)) {
                        extraBodyInvalid = true
                    } else {
                        val updated = buildProfile()
                        onSave(updated, buildSecrets(), draftProviderId) { revision, exists ->
                            draftRevision = revision
                            if (exists) draftProviderId = updated.id
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .weight(1f)
                    .oassistTouchTarget(),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(OAssistDimens.DenseSpacing),
                        strokeWidth = OAssistDimens.HairlineSpacing,
                    )
                    Text(validatingLabel)
                } else {
                    Text(saveLabel)
                }
            }
            OutlinedButton(
                onClick = onBack,
                enabled = !isSaving,
                modifier = Modifier
                    .weight(1f)
                    .oassistTouchTarget(),
            ) {
                Text(backLabel)
            }
        }

        if (!isNew && onDelete != null) {
            OutlinedButton(
                onClick = { profile?.id?.let(onDelete) },
                modifier = Modifier
                    .fillMaxWidth()
                    .oassistTouchTarget(),
            ) {
                Text(deleteLabel, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (saveError != null) {
        AlertDialog(
            onDismissRequest = onDismissSaveError,
            title = { Text(stringResource(R.string.provider_save_error_title)) },
            text = { Text(saveError) },
            confirmButton = {
                TextButton(
                    onClick = onDismissSaveError,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

@Composable
internal fun presetLabel(preset: ProviderPreset): String = when (preset) {
    ProviderPreset.OPENAI -> stringResource(R.string.provider_preset_openai)
    ProviderPreset.GOOGLE -> stringResource(R.string.provider_preset_google)
    ProviderPreset.ANTHROPIC -> stringResource(R.string.provider_preset_anthropic)
    ProviderPreset.OPENCODE -> stringResource(R.string.provider_preset_opencode)
    ProviderPreset.CUSTOM -> stringResource(R.string.provider_preset_custom)
}

@Composable
private fun presetDescription(preset: ProviderPreset): String = when (preset) {
    ProviderPreset.OPENAI -> stringResource(R.string.provider_preset_openai_desc)
    ProviderPreset.GOOGLE -> stringResource(R.string.provider_preset_google_desc)
    ProviderPreset.ANTHROPIC -> stringResource(R.string.provider_preset_anthropic_desc)
    ProviderPreset.OPENCODE -> stringResource(R.string.provider_preset_opencode_desc)
    ProviderPreset.CUSTOM -> stringResource(R.string.provider_preset_custom_desc)
}

@Composable
private fun googleVersionLabel(version: GoogleApiVersion): String = when (version) {
    GoogleApiVersion.BETA -> stringResource(R.string.provider_google_version_v1beta)
    GoogleApiVersion.V1 -> stringResource(R.string.provider_google_version_v1)
}

@Composable
private fun openCodeEndpointLabel(endpoint: OpenCodeEndpoint): String = when (endpoint) {
    OpenCodeEndpoint.ZEN -> stringResource(R.string.provider_opencode_zen)
    OpenCodeEndpoint.ZEN_GO -> stringResource(R.string.provider_opencode_zen_go)
}

@Composable
private fun protocolLabel(protocol: ModelProtocol): String = when (protocol) {
    ModelProtocol.OPENAI_COMPLETIONS -> stringResource(R.string.protocol_openai_completions)
    ModelProtocol.OPENAI_RESPONSES -> stringResource(R.string.protocol_openai_responses)
    ModelProtocol.ANTHROPIC_MESSAGES -> stringResource(R.string.protocol_anthropic_messages)
    ModelProtocol.GEMINI_GENERATE -> stringResource(R.string.protocol_gemini_generate)
}

@Composable
private fun fetchSchemeLabel(scheme: ModelFetchScheme): String = when (scheme) {
    ModelFetchScheme.MODELS -> stringResource(R.string.provider_fetch_models)
    ModelFetchScheme.NONE -> stringResource(R.string.provider_fetch_none)
}

@Composable
private fun authSchemeLabel(scheme: AuthScheme): String = when (scheme) {
    AuthScheme.NATIVE -> stringResource(R.string.provider_auth_native)
    AuthScheme.BEARER -> stringResource(R.string.provider_auth_bearer)
    AuthScheme.API_KEY_HEADER -> stringResource(R.string.provider_auth_api_key_header)
    AuthScheme.BASIC -> stringResource(R.string.provider_auth_basic)
    AuthScheme.NONE -> stringResource(R.string.provider_auth_none)
}

private fun generateId(): String = "p${System.currentTimeMillis()}"

/**
 * Nullable-enum не принимается авто-Saver'ом rememberSaveable: кодируем имя
 * константы строкой ("" — null), чтобы выбор протокола пережил пересоздание.
 */
private val modelProtocolSaver = Saver<ModelProtocol?, String>(
    save = { it?.name.orEmpty() },
    restore = { name -> ModelProtocol.entries.firstOrNull { it.name == name } },
)
