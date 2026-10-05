package ru.outsideproarts.oassist.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

@Composable
fun AssistantScreen(
    isAccessibilityEnabled: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit = {},
    isNotificationEnabled: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.assistant_title)
    val emptyTitle = stringResource(R.string.assistant_empty_title)
    val emptyBody = stringResource(R.string.assistant_empty_body)

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

        OAssistPanel(title = emptyTitle) {
            Text(
                text = emptyTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = emptyBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AccessSection(
            isAccessibilityEnabled = isAccessibilityEnabled,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            isNotificationEnabled = isNotificationEnabled,
            onOpenNotificationSettings = onOpenNotificationSettings,
        )
    }
}

@Composable
private fun AccessSection(
    isAccessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    isNotificationEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    val sectionTitle = stringResource(R.string.assistant_access_title)
    val a11yTitle = stringResource(R.string.assistant_access_a11y)
    val a11ySubtitle = stringResource(R.string.assistant_access_a11y_subtitle)
    val notifTitle = stringResource(R.string.assistant_access_notif)
    val notifSubtitle = stringResource(R.string.assistant_access_notif_subtitle)
    val onState = stringResource(R.string.assistant_access_on)
    val offState = stringResource(R.string.assistant_access_off)

    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
        OAssistSectionHeading(text = sectionTitle)
        OAssistPanel(title = sectionTitle) {
            AccessRow(
                title = a11yTitle,
                subtitle = a11ySubtitle,
                enabled = isAccessibilityEnabled,
                onState = onState,
                offState = offState,
                onClick = onOpenAccessibilitySettings,
            )
            AccessRow(
                title = notifTitle,
                subtitle = notifSubtitle,
                enabled = isNotificationEnabled,
                onState = onState,
                offState = offState,
                onClick = onOpenNotificationSettings,
            )
        }
    }
}

@Composable
private fun AccessRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onState: String,
    offState: String,
    onClick: () -> Unit,
) {
    val state = if (enabled) onState else offState
    OAssistSettingRow(
        title = title,
        subtitle = subtitle,
        value = state,
        icon = Icons.Default.ChevronRight,
        onClick = onClick,
        modifier = Modifier
            .oassistRole(Role.Button)
            .oassistTouchTarget()
            .semantics { stateDescription = state },
    )
}
