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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.SkillInfo
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSwitchRow
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading

/**
 * Навыки агента (`skills/<name>/SKILL.md`): список, вкл/выкл,
 * создание/правка/удаление. В системный промпт уходят только включённые.
 */
@Composable
fun SkillsScreen(
    skills: List<SkillInfo>,
    onSetEnabled: (String, Boolean) -> Unit,
    onSave: (name: String, title: String, body: String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.skills_title)
    val backLabel = stringResource(R.string.action_back)
    var editing by remember { mutableStateOf<SkillInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SkillInfo?>(null) }

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

        if (skills.isEmpty()) {
            OAssistPanel(title = title, showTitle = true) {
                Text(
                    text = stringResource(R.string.skills_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            skills.forEach { skill ->
                SkillRow(
                    skill = skill,
                    onSetEnabled = { onSetEnabled(skill.name, it) },
                    onEdit = { editing = skill },
                    onDelete = { deleting = skill },
                )
            }
        }

        OutlinedButton(
            onClick = { creating = true },
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.skills_add))
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Text(backLabel)
        }
    }

    if (creating) {
        SkillEditor(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, title, body ->
                onSave(name, title, body)
                creating = false
            },
        )
    }
    editing?.let { skill ->
        SkillEditor(
            initial = skill,
            onDismiss = { editing = null },
            onSave = { name, title, body ->
                onSave(name, title, body)
                editing = null
            },
        )
    }
    deleting?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.skills_delete_title, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(skill.name)
                    deleting = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SkillRow(
    skill: SkillInfo,
    onSetEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val onState = stringResource(R.string.state_enabled)
    val offState = stringResource(R.string.state_disabled)

    OAssistPanel(title = skill.title, showTitle = true) {
        Text(
            text = skill.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = skill.body.take(200),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Text(stringResource(R.string.skills_edit_title))
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.skills_delete_title, skill.name),
                )
            }
        }
        OAssistSwitchRow(
            checked = skill.enabled,
            onCheckedChange = onSetEnabled,
            label = stringResource(R.string.skills_enabled_label, skill.name),
            stateLabelOn = onState,
            stateLabelOff = offState,
        )
    }
}

@Composable
private fun SkillEditor(
    initial: SkillInfo?,
    onDismiss: () -> Unit,
    onSave: (name: String, title: String, body: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var body by remember { mutableStateOf(initial?.body ?: "") }
    val nameBad = name.isNotBlank() && !SkillInfo.NAME_PATTERN.matches(name)
    val canSave = !nameBad && name.isNotBlank() && body.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.skills_add else R.string.skills_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing)) {
                val skillNameLabel = stringResource(R.string.field_skill_name)
                val skillTitleLabel = stringResource(R.string.field_skill_title)
                OutlinedTextField(
                    value = name,
                    // Имя навыка — идентификатор: однострочный редактор
                    // без автокоррекции и заглавных.
                    onValueChange = { name = it.trim() },
                    label = { Text(stringResource(R.string.skills_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    readOnly = initial != null,
                    isError = nameBad,
                    supportingText = if (nameBad) {
                        { Text(stringResource(R.string.skills_name_bad)) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = skillNameLabel },
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.skills_heading)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = skillTitleLabel },
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.skills_body)) },
                    minLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, title.ifBlank { name }, body) },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
