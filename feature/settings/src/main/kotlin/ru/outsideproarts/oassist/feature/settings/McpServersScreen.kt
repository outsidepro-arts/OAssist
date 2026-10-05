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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.McpServerConfig
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

/**
 * Секреты из полей ввода при сохранении. Null — значение не менялось;
 * [clearBearer] — явное удаление сохранённого.
 */
data class McpSecretsInput(
    val bearer: String? = null,
    val clearBearer: Boolean = false,
)

@Composable
fun McpServersScreen(
    servers: List<McpServerConfig>,
    testingId: String?,
    onSave: (McpServerConfig, McpSecretsInput) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorFor by remember { mutableStateOf<McpServerConfig?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }

    if (editorFor != null || editorIsNew) {
        McpServerEditor(
            config = editorFor,
            testing = testingId == editorFor?.id,
            onSave = { updated, secrets ->
                onSave(updated, secrets)
                editorFor = null
                editorIsNew = false
            },
            onDelete = {
                editorFor?.id?.let(onDelete)
                editorFor = null
                editorIsNew = false
            },
            onTest = { id -> onTest(id) },
            onBack = {
                editorFor = null
                editorIsNew = false
            },
        )
    } else {
        McpServersList(
            servers = servers,
            onAdd = {
                editorFor = null
                editorIsNew = true
            },
            onEdit = { editorFor = it },
            onSetEnabled = onSetEnabled,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun McpServersList(
    servers: List<McpServerConfig>,
    onAdd: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.mcp_settings_title)
    val emptyText = stringResource(R.string.mcp_empty)
    val enabledState = stringResource(R.string.state_enabled)
    val disabledState = stringResource(R.string.state_disabled)

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

        OAssistSectionHeading(text = title)

        OAssistPanel(title = title) {
            OAssistSettingRow(
                title = stringResource(R.string.mcp_add),
                icon = Icons.Default.Add,
                onClick = onAdd,
                modifier = Modifier.oassistRole(Role.Button),
            )

            if (servers.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                servers.forEach { server ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OAssistSettingRow(
                            title = server.name,
                            subtitle = server.url,
                            value = if (server.enabled) enabledState else disabledState,
                            icon = Icons.Default.ChevronRight,
                            onClick = { onEdit(server) },
                            modifier = Modifier
                                .weight(1f)
                                .oassistRole(Role.Button)
                                .semantics {
                                    stateDescription = if (server.enabled) enabledState else disabledState
                                },
                        )
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { onSetEnabled(server.id, it) },
                            modifier = Modifier
                                .oassistTouchTarget()
                                .semantics {
                                    stateDescription = if (server.enabled) enabledState else disabledState
                                },
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun McpServerEditor(
    config: McpServerConfig?,
    testing: Boolean,
    onSave: (McpServerConfig, McpSecretsInput) -> Unit,
    onDelete: () -> Unit,
    onTest: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNew = config == null
    var name by remember { mutableStateOf(config?.name ?: "") }
    var url by remember { mutableStateOf(config?.url ?: "") }
    var enabled by remember { mutableStateOf(config?.enabled ?: true) }
    var bearerInput by remember { mutableStateOf("") }
    var hasSavedBearer by remember { mutableStateOf(config?.bearerRef != null) }
    var clearBearer by remember { mutableStateOf(false) }

    LaunchedEffect(config?.id) {
        if (config != null) {
            name = config.name
            url = config.url
            enabled = config.enabled
            hasSavedBearer = config.bearerRef != null
            clearBearer = false
            bearerInput = ""
        }
    }

    val title = if (isNew) {
        stringResource(R.string.mcp_add_title)
    } else {
        stringResource(R.string.mcp_edit_title)
    }
    val saveLabel = stringResource(R.string.action_save)
    val deleteLabel = stringResource(R.string.action_delete)
    val backLabel = stringResource(R.string.action_back)
    val replaceLabel = stringResource(R.string.action_replace)
    val enabledLabel = stringResource(R.string.provider_enabled)
    val enabledState = stringResource(R.string.state_enabled)
    val disabledState = stringResource(R.string.state_disabled)
    val maskedLabel = stringResource(R.string.provider_api_key_masked)
    val canSave = name.isNotBlank() && url.isNotBlank()

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
                label = { Text(stringResource(R.string.mcp_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.mcp_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = bearerInput,
                onValueChange = {
                    bearerInput = it
                    if (it.isNotEmpty()) clearBearer = false
                },
                label = {
                    Text(
                        if (hasSavedBearer) {
                            maskedLabel
                        } else {
                            stringResource(R.string.mcp_bearer)
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasSavedBearer && !clearBearer) {
                Row(horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing)) {
                    OutlinedButton(
                        onClick = {
                            hasSavedBearer = false
                            bearerInput = ""
                        },
                        modifier = Modifier.oassistTouchTarget(),
                    ) {
                        Text(replaceLabel)
                    }
                    OutlinedButton(
                        onClick = {
                            hasSavedBearer = false
                            clearBearer = true
                            bearerInput = ""
                        },
                        modifier = Modifier.oassistTouchTarget(),
                    ) {
                        Text(deleteLabel)
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

            if (!isNew) {
                OutlinedButton(
                    onClick = { config?.id?.let(onTest) },
                    enabled = !testing,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(OAssistDimens.DenseSpacing),
                            strokeWidth = OAssistDimens.HairlineSpacing,
                        )
                    } else {
                        Text(stringResource(R.string.mcp_test))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            Button(
                onClick = {
                    val updated = (config ?: McpServerConfig(id = generateMcpId(), name = "", url = "")).copy(
                        name = name,
                        url = url.trim(),
                        enabled = enabled,
                    )
                    onSave(
                        updated,
                        McpSecretsInput(
                            bearer = bearerInput.ifBlank { null },
                            clearBearer = clearBearer,
                        ),
                    )
                },
                enabled = canSave,
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

        if (!isNew) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .oassistTouchTarget(),
            ) {
                Text(deleteLabel, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun generateMcpId(): String = "m${System.currentTimeMillis()}"
