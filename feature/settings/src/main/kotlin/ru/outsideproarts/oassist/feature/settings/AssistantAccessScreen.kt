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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

/**
 * Раздел «Доступ» в настройках: системные сервисы (экран, уведомления,
 * Shizuku/root). Сюда переехал бывший таб «Ассистент» — отдельного таба нет.
 */
@Composable
fun AssistantAccessScreen(
    isAccessibilityEnabled: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit = {},
    isNotificationEnabled: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    isWriteSettingsGranted: Boolean = false,
    onOpenWriteSettings: () -> Unit = {},
    isShizukuGranted: Boolean = false,
    onRequestShizuku: () -> Unit = {},
    isRootAvailable: Boolean = false,
    bgStartVisible: Boolean = false,
    bgStartAllowed: Boolean = true,
    bgStartCommand: String = "",
    canFixBgStart: Boolean = false,
    /** null — успех, иначе текст ошибки. */
    onFixBgStart: suspend () -> String? = { null },
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_assistant_title)
    val sectionTitle = stringResource(R.string.assistant_access_title)
    val backLabel = stringResource(R.string.action_back)
    val onState = stringResource(R.string.assistant_access_on)
    val offState = stringResource(R.string.assistant_access_off)

    var showBgStartDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var fixResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bgStartVisible, bgStartAllowed) {
        if (bgStartVisible && !bgStartAllowed) showBgStartDialog = true
    }

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
                title = stringResource(R.string.assistant_access_write_settings),
                subtitle = stringResource(R.string.assistant_access_write_settings_subtitle),
                enabled = isWriteSettingsGranted,
                onState = onState,
                offState = offState,
                onClick = onOpenWriteSettings,
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
            if (bgStartVisible) {
                AccessRow(
                    title = stringResource(R.string.assistant_access_bg_start),
                    subtitle = stringResource(R.string.assistant_access_bg_start_subtitle),
                    enabled = bgStartAllowed,
                    onState = onState,
                    offState = offState,
                    onClick = { fixResult = null; showBgStartDialog = true },
                )
            }
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

    if (showBgStartDialog) {
        val okLabel = stringResource(R.string.action_ok)
        val fixLabel = stringResource(R.string.bg_start_dialog_fix)
        val doneText = stringResource(R.string.bg_start_dialog_fix_done)
        val errorTemplate = stringResource(R.string.bg_start_dialog_fix_error)
        AlertDialog(
            onDismissRequest = { showBgStartDialog = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.bg_start_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.HairlineSpacing)) {
                    Text(stringResource(R.string.bg_start_dialog_text, bgStartCommand))
                    fixResult?.let { Text(it) }
                }
            },
            confirmButton = {
                if (canFixBgStart) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val error = onFixBgStart()
                                fixResult = if (error == null) doneText else errorTemplate.format(error)
                            }
                        },
                        modifier = Modifier.oassistTouchTarget(),
                    ) {
                        Text(fixLabel)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBgStartDialog = false },
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Text(okLabel)
                }
            },
        )
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
    OAssistSettingRow(
        title = title,
        subtitle = subtitle,
        value = state,
        icon = if (onClick != null) Icons.Default.ChevronRight else null,
        onClick = onClick,
        modifier = Modifier
            .oassistTouchTarget()
            .semantics { stateDescription = state },
    )
}
