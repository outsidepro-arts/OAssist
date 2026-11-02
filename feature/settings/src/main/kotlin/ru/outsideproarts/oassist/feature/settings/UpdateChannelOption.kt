package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import ru.outsideproarts.oassist.core.ui.OAssistDimens

@Composable
internal fun UpdateChannelOption(channel: UpdateChannel, selected: Boolean, enabled: Boolean, onSelect: (UpdateChannel) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().oassistTouchTarget()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(channel) })
            .oassistRole(Role.RadioButton).padding(vertical = OAssistDimens.OptionRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(stringResource(when (channel) {
            UpdateChannel.STABLE -> R.string.update_channel_stable_title
            UpdateChannel.PRERELEASE -> R.string.update_channel_prerelease_title
            UpdateChannel.DEVELOPMENT -> R.string.update_channel_development_title
        }))
    }
}
