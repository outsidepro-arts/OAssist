package ru.outsideproarts.oassist.feature.settings

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
import androidx.compose.material3.OutlinedButton
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
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

/**
 * Раздел «Ассистент» в настройках: системные доступы (экран, уведомления,
 * Shizuku/root). Сюда переехал бывший таб «Ассистент» — отдельного таба нет.
 */
@Composable
fun AssistantAccessScreen(
    isAccessibilityEnabled: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit = {},
    isNotificationEnabled: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    isShizukuGranted: Boolean = false,
    onRequestShizuku: () -> Unit = {},
    isRootAvailable: Boolean = false,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_assistant_title)
    val sectionTitle = stringResource(R.string.assistant_access_title)
    val backLabel = stringResource(R.string.action_back)
    val onState = stringResource(R.string.assistant_access_on)
    val offState = stringResource(R.string.assistant_access_off)

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

        OAssistPanel(title = sectionTitle) {
            AccessRow(
                title = stringResource(R.string.assistant_access_a11y),
                subtitle = stringResource(R.string.assistant_access_a11y_subtitle),
                enabled = isAccessibilityEnabled,
                onState = onState,
                offState = offState,
                onClick = onOpenAccessibilitySettings,
            )
            AccessRow(
                title = stringResource(R.string.assistant_access_notif),
                subtitle = stringResource(R.string.assistant_access_notif_subtitle),
                enabled = isNotificationEnabled,
                onState = onState,
                offState = offState,
                onClick = onOpenNotificationSettings,
            )
            AccessRow(
                title = stringResource(R.string.assistant_access_shizuku),
                subtitle = stringResource(R.string.assistant_access_shizuku_subtitle),
                enabled = isShizukuGranted,
                onState = onState,
                offState = offState,
                onClick = onRequestShizuku,
            )
            AccessRow(
                title = stringResource(R.string.assistant_access_root),
                subtitle = stringResource(R.string.assistant_access_root_subtitle),
                enabled = isRootAvailable,
                onState = onState,
                offState = offState,
                onClick = null,
            )
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
}

@Composable
private fun AccessRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onState: String,
    offState: String,
    onClick: (() -> Unit)?,
) {
    val state = if (enabled) onState else offState
    val roleMod = if (onClick != null) Modifier else Modifier
    OAssistSettingRow(
        title = title,
        subtitle = subtitle,
        value = state,
        icon = if (onClick != null) Icons.Default.ChevronRight else null,
        onClick = onClick,
        modifier = Modifier
            .then(roleMod)
            .oassistTouchTarget()
            .semantics { stateDescription = state },
    )
}
