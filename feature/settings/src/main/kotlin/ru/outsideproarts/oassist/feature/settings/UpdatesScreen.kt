package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.model.update.*
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.*

@Composable
fun UpdatesScreen(
    preferences: UpdatePreferences,
    installed: InstalledBuild,
    candidate: UpdateCandidate?,
    status: String,
    busy: Boolean,
    downloading: Boolean,
    progress: Int,
    notificationsAllowed: Boolean,
    onAutomaticChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onChannelChange: (UpdateChannel) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OAssistDimens.ScreenContentPadding),
        verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.update_back)) }
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.oassistHeading())
        Text(stringResource(R.string.update_installed, installed.versionName, installed.versionCode))
        OAssistSwitchRow(
            checked = preferences.automaticChecks, onCheckedChange = onAutomaticChange,
            label = stringResource(R.string.update_automatic),
            stateLabelOn = stringResource(R.string.update_enabled), stateLabelOff = stringResource(R.string.update_disabled),
            enabled = !downloading,
        )
        if (preferences.automaticChecks) {
            OAssistSwitchRow(
                checked = preferences.notifications, onCheckedChange = onNotificationsChange,
                label = stringResource(R.string.update_notifications),
                stateLabelOn = stringResource(R.string.update_enabled), stateLabelOff = stringResource(R.string.update_disabled),
            )
            if (preferences.notifications && !notificationsAllowed) {
                Text(stringResource(R.string.update_notifications_blocked))
                TextButton(onClick = onNotificationSettings) { Text(stringResource(R.string.update_notification_settings)) }
            }
        }
        OAssistPanel(title = stringResource(R.string.update_channel), showTitle = true) {
            val selected = preferences.effectiveChannel(installed)
            UpdateChannel.entries.forEach { channel ->
                // The whole row is one accessible, labelled action with the selected state.
                UpdateChannelOption(channel, channel == selected, !downloading, onChannelChange)
            }
            Text(stringResource(when (preferences.channelReason(installed)) {
                UpdateChannelReason.MANUAL -> R.string.update_channel_manual
                UpdateChannelReason.AUTOMATIC_DEBUG -> R.string.update_channel_debug
                UpdateChannelReason.AUTOMATIC_PRERELEASE -> R.string.update_channel_prerelease
                UpdateChannelReason.AUTOMATIC_STABLE -> R.string.update_channel_stable
            }))
            Text(stringResource(when (selected) {
                UpdateChannel.STABLE -> R.string.update_interval_stable
                UpdateChannel.PRERELEASE -> R.string.update_interval_prerelease
                UpdateChannel.DEVELOPMENT -> R.string.update_interval_development
            }))
        }
        OAssistLiveStatus(status)
        if (downloading) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_progress, progress))
            TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.update_cancel_download)) }
        }
        Button(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.update_check_now)) }
        if (candidate != null) {
            OAssistPanel(title = stringResource(R.string.update_available), showTitle = true) {
                Text(stringResource(R.string.update_candidate, candidate.manifest.versionName, candidate.manifest.versionCode,
                    candidate.manifest.apkSize / (1024.0 * 1024.0)))
                if (candidate.releaseNotes.isNotBlank()) Text(candidate.releaseNotes)
                Button(onClick = onDownload, enabled = !busy) { Text(stringResource(R.string.update_download_install)) }
            }
        }
        Text(stringResource(R.string.update_install_explanation))
    }
}
