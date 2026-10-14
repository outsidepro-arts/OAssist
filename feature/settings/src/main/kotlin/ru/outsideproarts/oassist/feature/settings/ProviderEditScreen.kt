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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
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
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSwitchRow
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading

/**
 * Секреты из полей ввода при сохранении. Null — значение не менялось;
 * [clearApiKey] — явное удаление сохранённого.
 */
data class ProviderSecretsInput(
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    profile: ProviderProfile?,
    isSaving: Boolean,
    saveError: String?,
    onSave: (ProviderProfile, ProviderSecretsInput) -> Unit,
    onDismissSaveError: () -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNew = profile == null
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var kind by remember { mutableStateOf(profile?.kind ?: ProviderKind.OPENAI_COMPAT) }
    var baseUrl by remember { mutableStateOf(profile?.baseUrl ?: "") }
    var enabled by remember { mutableStateOf(profile?.enabled ?: true) }
    var apiKeyInput by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(profile?.apiKeyRef != null) }
    var clearApiKey by remember { mutableStateOf(false) }
    // Показ секрета ввода: по умолчанию маскирован.
    var apiKeyVisible by remember { mutableStateOf(false) }
    // Тип провайдера — чистый dropdown (без ввода): значение только из списка.
    var kindExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(profile?.id) {
        if (profile != null) {
            name = profile.name
            kind = profile.kind
            baseUrl = profile.baseUrl
            enabled = profile.enabled
            hasSavedKey = profile.apiKeyRef != null
            clearApiKey = false
            apiKeyInput = ""
        }
    }

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
                label = { Text(stringResource(R.string.provider_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = nameLabel },
            )

            OAssistSectionHeading(text = stringResource(R.string.provider_kind))
            ExposedDropdownMenuBox(
                expanded = kindExpanded,
                onExpandedChange = { kindExpanded = it },
            ) {
                OutlinedTextField(
                    value = kind.name,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.provider_kind)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kindExpanded) },
                    supportingText = { Text(kindDescription(kind)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
                )
                ExposedDropdownMenu(
                    expanded = kindExpanded,
                    onDismissRequest = { kindExpanded = false },
                ) {
                    ProviderKind.entries.forEach { k ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(k.name)
                                    Text(
                                        text = kindDescription(k),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                kind = k
                                kindExpanded = false
                            },
                        )
                    }
                }
            }

            val baseUrlLabel = stringResource(R.string.field_provider_url)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.provider_base_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = baseUrlLabel },
            )

            val apiKeyLabel = stringResource(R.string.field_provider_key)
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    if (it.isNotEmpty()) clearApiKey = false
                },
                label = {
                    Text(
                        if (hasSavedKey) {
                            maskedLabel
                        } else {
                            stringResource(R.string.provider_api_key)
                        },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = apiKeyLabel },
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
                    val updated = (profile ?: ProviderProfile(id = generateId(), name = "", kind = kind, baseUrl = "")).copy(
                        name = name,
                        kind = kind,
                        baseUrl = baseUrl,
                        enabled = enabled,
                    )
                    onSave(
                        updated,
                        ProviderSecretsInput(
                            apiKey = apiKeyInput.ifBlank { null },
                            clearApiKey = clearApiKey,
                        ),
                    )
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
private fun kindDescription(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI_COMPAT -> stringResource(R.string.provider_kind_openai_compat_desc)
    ProviderKind.ANTHROPIC -> stringResource(R.string.provider_kind_anthropic_desc)
    ProviderKind.GEMINI -> stringResource(R.string.provider_kind_gemini_desc)
    ProviderKind.OPENAI_RESPONSES -> stringResource(R.string.provider_kind_openai_responses_desc)
    ProviderKind.OPENCODE -> stringResource(R.string.provider_kind_opencode_desc)
}

private fun generateId(): String = "p${System.currentTimeMillis()}"
