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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

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
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_title)
    val selectedState = stringResource(R.string.state_selected)
    val unselectedState = stringResource(R.string.state_unselected)

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

        LanguageSection(
            language = language,
            onLanguageChange = onLanguageChange,
            selectedState = selectedState,
            unselectedState = unselectedState,
        )

        ProvidersSection(onNavigateToProviders = onNavigateToProviders)

        ModesSection(onNavigateToModeConfig = onNavigateToModeConfig)

        ToolsSection(onNavigateToTools = onNavigateToTools)

        McpSection(onNavigateToMcp = onNavigateToMcp)

        SkillsSection(onNavigateToSkills = onNavigateToSkills)

        MemorySection(onNavigateToMemory = onNavigateToMemory)

        AboutSection()
    }
}

@Composable
private fun LanguageSection(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    selectedState: String,
    unselectedState: String,
) {
    val sectionTitle = stringResource(R.string.settings_language_title)
    val sectionSubtitle = stringResource(R.string.settings_language_subtitle)
    val groupLabel = stringResource(R.string.settings_language_picker)

    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
        AppLanguage.RUSSIAN to stringResource(R.string.settings_language_russian),
        AppLanguage.ENGLISH to stringResource(R.string.settings_language_english),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
    ) {
        OAssistSectionHeading(text = sectionTitle)

        OAssistPanel(title = sectionTitle, stateDescription = sectionSubtitle) {
            Text(
                text = sectionSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .semantics { paneTitle = groupLabel },
                verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                options.forEach { (value, label) ->
                    val selected = language == value
                    val state = if (selected) selectedState else unselectedState
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .oassistTouchTarget()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onLanguageChange(value) },
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
        }
    }
}

@Composable
private fun ProvidersSection(onNavigateToProviders: () -> Unit) {
    val sectionTitle = stringResource(R.string.providers_title)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            OAssistSettingRow(
                title = sectionTitle,
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToProviders,
                modifier = Modifier.oassistRole(Role.Button),
            )
        }
    }
}

@Composable
private fun ModesSection(onNavigateToModeConfig: (String) -> Unit) {
    val sectionTitle = stringResource(R.string.mode_config_title)
    val modeChat = stringResource(R.string.mode_chat)
    val modeAssistant = stringResource(R.string.mode_assistant)
    val modeShare = stringResource(R.string.mode_share)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            OAssistSettingRow(
                title = modeChat,
                icon = Icons.Default.ChevronRight,
                onClick = { onNavigateToModeConfig("CHAT") },
                modifier = Modifier.oassistRole(Role.Button),
            )
            OAssistSettingRow(
                title = modeAssistant,
                icon = Icons.Default.ChevronRight,
                onClick = { onNavigateToModeConfig("ASSISTANT") },
                modifier = Modifier.oassistRole(Role.Button),
            )
            OAssistSettingRow(
                title = modeShare,
                icon = Icons.Default.ChevronRight,
                onClick = { onNavigateToModeConfig("SHARE") },
                modifier = Modifier.oassistRole(Role.Button),
            )
        }
    }
}

@Composable
private fun ToolsSection(onNavigateToTools: () -> Unit) {
    val sectionTitle = stringResource(R.string.tools_settings_title)
    val sectionSubtitle = stringResource(R.string.tools_settings_subtitle)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            OAssistSettingRow(
                title = sectionTitle,
                subtitle = sectionSubtitle,
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToTools,
                modifier = Modifier.oassistRole(Role.Button),
            )
        }
    }
}

@Composable
private fun McpSection(onNavigateToMcp: () -> Unit) {
    val sectionTitle = stringResource(R.string.mcp_settings_title)
    val sectionSubtitle = stringResource(R.string.mcp_settings_subtitle)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            OAssistSettingRow(
                title = sectionTitle,
                subtitle = sectionSubtitle,
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToMcp,
                modifier = Modifier.oassistRole(Role.Button),
            )
        }
    }
}

@Composable
private fun SkillsSection(onNavigateToSkills: () -> Unit) {
    val sectionTitle = stringResource(R.string.skills_title)
    val sectionSubtitle = stringResource(R.string.skills_subtitle)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            OAssistSettingRow(
                title = sectionTitle,
                subtitle = sectionSubtitle,
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToSkills,
                modifier = Modifier.oassistRole(Role.Button),
            )
        }
    }
}

@Composable
private fun MemorySection(onNavigateToMemory: () -> Unit) {
    val sectionTitle = stringResource(R.string.memory_title)
    val sectionSubtitle = stringResource(R.string.memory_subtitle)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            OAssistSettingRow(
                title = sectionTitle,
                subtitle = sectionSubtitle,
                icon = Icons.Default.ChevronRight,
                onClick = onNavigateToMemory,
                modifier = Modifier.oassistRole(Role.Button),
            )
        }
    }
}

@Composable
private fun AboutSection() {
    val sectionTitle = stringResource(R.string.settings_about_title)
    val versionLabel = stringResource(R.string.settings_about_version)
    val licenseLabel = stringResource(R.string.settings_about_license)
    val licenseValue = stringResource(R.string.settings_about_license_value)
    val versionValue = stringResource(R.string.app_version)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = versionLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = versionValue,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = licenseLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = licenseValue,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
