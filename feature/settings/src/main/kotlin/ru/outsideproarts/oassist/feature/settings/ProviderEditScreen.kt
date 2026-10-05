package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading

/**
 * Секреты из полей ввода при сохранении. Null — значение не менялось;
 * [clearApiKey]/[clearBasic] — явное удаление сохранённого.
 * Basic Auth — только для OPENCODE_ZEN (свой `opencode serve`).
 */
data class ProviderSecretsInput(
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
    val basicUser: String? = null,
    val basicPassword: String? = null,
    val clearBasic: Boolean = false,
)

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
    var basicUserInput by remember { mutableStateOf("") }
    var basicPasswordInput by remember { mutableStateOf("") }
    var hasSavedBasic by remember { mutableStateOf(profile?.basicAuthPasswordRef != null) }
    var clearBasic by remember { mutableStateOf(false) }

    LaunchedEffect(profile?.id) {
        if (profile != null) {
            name = profile.name
            kind = profile.kind
            baseUrl = profile.baseUrl
            enabled = profile.enabled
            hasSavedKey = profile.apiKeyRef != null
            clearApiKey = false
            hasSavedBasic = profile.basicAuthPasswordRef != null
            clearBasic = false
            basicUserInput = ""
            basicPasswordInput = ""
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.provider_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OAssistSectionHeading(text = stringResource(R.string.provider_kind))
            val kindLabel = stringResource(R.string.provider_kind)
            val selectedLabel = stringResource(R.string.state_selected)
            val unselectedLabel = stringResource(R.string.state_unselected)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .semantics { paneTitle = kindLabel },
                verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                ProviderKind.entries.forEach { k ->
                    val selected = kind == k
                    val state = if (selected) selectedLabel else unselectedLabel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .oassistTouchTarget()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { kind = k },
                            )
                            .semantics { stateDescription = state }
                            .padding(horizontal = OAssistDimens.DenseSpacing, vertical = OAssistDimens.ItemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.OptionRowSpacing),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = k.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.oassistRole(Role.RadioButton),
                            )
                            Text(
                                text = kindDescription(k),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.provider_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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

            if (kind == ProviderKind.OPENCODE_ZEN) {
                OutlinedTextField(
                    value = basicUserInput,
                    onValueChange = {
                        basicUserInput = it
                        if (it.isNotEmpty()) clearBasic = false
                    },
                    label = { Text(stringResource(R.string.provider_basic_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = basicPasswordInput,
                    onValueChange = {
                        basicPasswordInput = it
                        if (it.isNotEmpty()) clearBasic = false
                    },
                    label = {
                        Text(
                            if (hasSavedBasic) {
                                maskedLabel
                            } else {
                                stringResource(R.string.provider_basic_password)
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasSavedBasic && !clearBasic) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing)) {
                        OutlinedButton(
                            onClick = {
                                hasSavedBasic = false
                                basicUserInput = ""
                                basicPasswordInput = ""
                            },
                            modifier = Modifier.oassistTouchTarget(),
                        ) {
                            Text(replaceLabel)
                        }
                        OutlinedButton(
                            onClick = {
                                hasSavedBasic = false
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = enabledLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.oassistRole(Role.Switch),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    modifier = Modifier.semantics {
                        stateDescription = if (enabled) enabledState else disabledState
                    },
                )
            }
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
                            basicUser = basicUserInput.ifBlank { null },
                            basicPassword = basicPasswordInput.ifBlank { null },
                            clearBasic = clearBasic,
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
            ProviderKind.OPENCODE_ZEN -> stringResource(R.string.provider_kind_opencode_desc)
}

private fun generateId(): String = "p${System.currentTimeMillis()}"
