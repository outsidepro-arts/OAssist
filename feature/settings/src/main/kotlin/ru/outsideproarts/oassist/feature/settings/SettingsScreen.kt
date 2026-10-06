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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import ru.outsideproarts.oassist.core.a11y.oassistDropdownClassName
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

/**
 * Настройки — плоский список разделов (без лишних заголовков):
 * язык-dropdown + кнопки разделов. Каждый раздел — один touch target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onNavigateToProviders: () -> Unit,
    onNavigateToModeConfig: (String) -> Unit,
    onNavigateToTools: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToMemory: () -> Unit = {},
    onNavigateToAssistant: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onWipe: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_title)
    var languageExpanded by remember { mutableStateOf(false) }
    val languageOptions = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
        AppLanguage.RUSSIAN to stringResource(R.string.settings_language_russian),
        AppLanguage.ENGLISH to stringResource(R.string.settings_language_english),
    )

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

        OAssistPanel(title = title, showTitle = true) {
            ExposedDropdownMenuBox(
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = it },
            ) {
                OutlinedTextField(
                    value = languageOptions.firstOrNull { it.first == language }?.second
                        ?: languageOptions.first().second,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.settings_language_title)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
                )
                ExposedDropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false },
                ) {
                    languageOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onLanguageChange(value)
                                languageExpanded = false
                            },
                        )
                    }
                }
            }

            OAssistSettingRow(
                title = stringResource(R.string.providers_title),
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToProviders,
            )
            OAssistSettingRow(
                title = stringResource(R.string.mode_chat),
                subtitle = stringResource(R.string.mode_config_title),
                icon = Icons.Default.ChevronRight,
                onClick = { onNavigateToModeConfig("CHAT") },
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.mode_assistant),
                subtitle = stringResource(R.string.mode_config_title),
                icon = Icons.Default.ChevronRight,
                onClick = { onNavigateToModeConfig("ASSISTANT") },
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.mode_share),
                subtitle = stringResource(R.string.mode_config_title),
                icon = Icons.Default.ChevronRight,
                onClick = { onNavigateToModeConfig("SHARE") },
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.settings_assistant_title),
                subtitle = stringResource(R.string.settings_assistant_subtitle),
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToAssistant,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.tools_settings_title),
                subtitle = stringResource(R.string.tools_settings_subtitle),
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToTools,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.mcp_settings_title),
                subtitle = stringResource(R.string.mcp_settings_subtitle),
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToMcp,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.skills_title),
                subtitle = stringResource(R.string.skills_subtitle),
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToSkills,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.memory_title),
                subtitle = stringResource(R.string.memory_subtitle),
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToMemory,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.data_export),
                icon = Icons.Default.ChevronRight,
                onClick = onExport,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.data_import),
                icon = Icons.Default.ChevronRight,
                onClick = onImport,
                
            )
            OAssistSettingRow(
                title = stringResource(R.string.data_wipe),
                icon = Icons.Default.ChevronRight,
                onClick = onWipe,
                
            )
        }

        OAssistPanel(title = stringResource(R.string.settings_about_title), showTitle = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_about_version),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.app_version),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_about_license),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_about_license_value),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
