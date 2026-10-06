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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

@Composable
fun ProviderListScreen(
    providers: List<ProviderProfile>,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.providers_title)
    val emptyText = stringResource(R.string.providers_empty)
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
                title = stringResource(R.string.providers_add),
                icon = Icons.Default.Add,
                onClick = onAddProvider,
            )

            if (providers.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                providers.forEach { profile ->
                    OAssistSettingRow(
                        title = profile.name,
                        subtitle = "${profile.kind.name} · ${profile.baseUrl}",
                        value = if (profile.enabled) enabledState else disabledState,
                        icon = Icons.Default.ChevronRight,
                        onClick = { onEditProvider(profile.id) },
                        modifier = Modifier
                            
                            .semantics {
                                stateDescription = if (profile.enabled) enabledState else disabledState
                            },
                    )
                }
            }
        }
    }
}
