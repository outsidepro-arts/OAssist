package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ru.outsideproarts.oassist.core.agent.ToolDescriptor
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow

/**
 * «Инструменты агента»: какие инструменты доступны агенту и когда спрашивать.
 * Безопасность (сброс «всегда разрешать», сброс к дефолтам) — в этом же разделе.
 */
@Composable
fun ToolsSettingsScreen(
    tools: List<ToolDescriptor>,
    disabled: Set<String>,
    askOverrides: Map<String, Boolean>,
    toolPermissions: Map<String, List<String>> = emptyMap(),
    missingPermissions: Set<String> = emptySet(),
    onToggleEnabled: (String, Boolean) -> Unit,
    onToggleAsk: (String, Boolean) -> Unit,
    onGrantPermission: (String) -> Unit = {},
    onResetApprovals: () -> Unit,
    onResetTools: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.tools_settings_title)
    val toolsSection = stringResource(R.string.tools_section_tools)
    val securitySection = stringResource(R.string.approvals_title)
    val backLabel = stringResource(R.string.action_back)

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

        Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
            OAssistSectionHeading(text = toolsSection)
            Text(
                text = stringResource(R.string.tools_perm_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tools.forEach { descriptor ->
                val enabled = descriptor.name !in disabled
                val askDefault = descriptor.defaultRisk != ActionRisk.AUTO
                ToolRow(
                    descriptor = descriptor,
                    enabled = enabled,
                    ask = askOverrides[descriptor.name] ?: askDefault,
                    missingPermissions = (toolPermissions[descriptor.name] ?: emptyList())
                        .filter { it in missingPermissions },
                    onToggleEnabled = { onToggleEnabled(descriptor.name, it) },
                    onToggleAsk = { onToggleAsk(descriptor.name, it) },
                    onGrantPermission = onGrantPermission,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
            OAssistSectionHeading(text = securitySection)
            OAssistPanel(title = securitySection) {
                OAssistSettingRow(
                    title = stringResource(R.string.approvals_reset),
                    subtitle = stringResource(R.string.approvals_reset_subtitle),
                    onClick = onResetApprovals,
                    modifier = Modifier.oassistRole(Role.Button),
                )
                OAssistSettingRow(
                    title = stringResource(R.string.tools_reset),
                    subtitle = stringResource(R.string.tools_reset_subtitle),
                    onClick = onResetTools,
                    modifier = Modifier.oassistRole(Role.Button),
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
}

@Composable
private fun ToolRow(
    descriptor: ToolDescriptor,
    enabled: Boolean,
    ask: Boolean,
    missingPermissions: List<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAsk: (Boolean) -> Unit,
    onGrantPermission: (String) -> Unit,
) {
    val title = toolTitle(descriptor.name)
    val description = toolDescription(descriptor.name)
    val enabledLabel = stringResource(R.string.tool_enabled)
    val askLabel = stringResource(R.string.tool_ask)
    val grantLabel = stringResource(R.string.tools_perm_grant)
    val onState = stringResource(R.string.state_enabled)
    val offState = stringResource(R.string.state_disabled)

    OAssistPanel(title = title) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            label = enabledLabel,
            checked = enabled,
            state = if (enabled) onState else offState,
            onCheckedChange = onToggleEnabled,
        )
        if (enabled) {
            SwitchRow(
                label = askLabel,
                checked = ask,
                state = if (ask) onState else offState,
                onCheckedChange = onToggleAsk,
            )
        }
        missingPermissions.forEach { permission ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                Text(
                    text = permissionLabel(permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onGrantPermission(permission) },
                    modifier = Modifier.oassistTouchTarget().oassistRole(Role.Button),
                ) {
                    Text(grantLabel)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    state: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .oassistTouchTarget()
                .semantics { stateDescription = state },
        )
    }
}

@Composable
private fun toolTitle(name: String): String = when (name) {
    "app_launch" -> stringResource(R.string.tool_app_launch_title)
    "web_open" -> stringResource(R.string.tool_web_open_title)
    "web_search" -> stringResource(R.string.tool_web_search_title)
    "alarm_set" -> stringResource(R.string.tool_alarm_set_title)
    "open_settings" -> stringResource(R.string.tool_open_settings_title)
    "call_place" -> stringResource(R.string.tool_call_place_title)
    "sms_send" -> stringResource(R.string.tool_sms_send_title)
    "contact_find" -> stringResource(R.string.tool_contact_find_title)
    "calendar_add" -> stringResource(R.string.tool_calendar_add_title)
    "timer_set" -> stringResource(R.string.tool_timer_set_title)
    "device_info" -> stringResource(R.string.tool_device_info_title)
    "clipboard_copy" -> stringResource(R.string.tool_clipboard_copy_title)
    "ui_get_screen" -> stringResource(R.string.tool_ui_get_screen_title)
    "ui_click" -> stringResource(R.string.tool_ui_click_title)
    "ui_long_click" -> stringResource(R.string.tool_ui_long_click_title)
    "ui_set_text" -> stringResource(R.string.tool_ui_set_text_title)
    "ui_scroll" -> stringResource(R.string.tool_ui_scroll_title)
    "ui_swipe" -> stringResource(R.string.tool_ui_swipe_title)
    "ui_global" -> stringResource(R.string.tool_ui_global_title)
    "ui_wait_for" -> stringResource(R.string.tool_ui_wait_for_title)
    "notify_list" -> stringResource(R.string.tool_notify_list_title)
    "notify_open" -> stringResource(R.string.tool_notify_open_title)
    "notify_clear" -> stringResource(R.string.tool_notify_clear_title)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_title)
    "nav_open" -> stringResource(R.string.tool_nav_open_title)
    "share_text" -> stringResource(R.string.tool_share_text_title)
    "flashlight" -> stringResource(R.string.tool_flashlight_title)
    "media_button" -> stringResource(R.string.tool_media_button_title)
    "location_get" -> stringResource(R.string.tool_location_get_title)
    "device_state" -> stringResource(R.string.tool_device_state_title)
    "voice_listen_start" -> stringResource(R.string.tool_voice_listen_start_title)
    "voice_listen_stop" -> stringResource(R.string.tool_voice_listen_stop_title)
    "memory_get" -> stringResource(R.string.tool_memory_get_title)
    "memory_put" -> stringResource(R.string.tool_memory_put_title)
    "memory_forget" -> stringResource(R.string.tool_memory_forget_title)
    "skill_list" -> stringResource(R.string.tool_skill_list_title)
    "skill_get" -> stringResource(R.string.tool_skill_get_title)
    else -> name
}

@Composable
private fun toolDescription(name: String): String = when (name) {
    "app_launch" -> stringResource(R.string.tool_app_launch_desc)
    "web_open" -> stringResource(R.string.tool_web_open_desc)
    "web_search" -> stringResource(R.string.tool_web_search_desc)
    "alarm_set" -> stringResource(R.string.tool_alarm_set_desc)
    "open_settings" -> stringResource(R.string.tool_open_settings_desc)
    "call_place" -> stringResource(R.string.tool_call_place_desc)
    "sms_send" -> stringResource(R.string.tool_sms_send_desc)
    "contact_find" -> stringResource(R.string.tool_contact_find_desc)
    "calendar_add" -> stringResource(R.string.tool_calendar_add_desc)
    "timer_set" -> stringResource(R.string.tool_timer_set_desc)
    "device_info" -> stringResource(R.string.tool_device_info_desc)
    "clipboard_copy" -> stringResource(R.string.tool_clipboard_copy_desc)
    "ui_get_screen" -> stringResource(R.string.tool_ui_get_screen_desc)
    "ui_click" -> stringResource(R.string.tool_ui_click_desc)
    "ui_long_click" -> stringResource(R.string.tool_ui_long_click_desc)
    "ui_set_text" -> stringResource(R.string.tool_ui_set_text_desc)
    "ui_scroll" -> stringResource(R.string.tool_ui_scroll_desc)
    "ui_swipe" -> stringResource(R.string.tool_ui_swipe_desc)
    "ui_global" -> stringResource(R.string.tool_ui_global_desc)
    "ui_wait_for" -> stringResource(R.string.tool_ui_wait_for_desc)
    "notify_list" -> stringResource(R.string.tool_notify_list_desc)
    "notify_open" -> stringResource(R.string.tool_notify_open_desc)
    "notify_clear" -> stringResource(R.string.tool_notify_clear_desc)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_desc)
    "nav_open" -> stringResource(R.string.tool_nav_open_desc)
    "share_text" -> stringResource(R.string.tool_share_text_desc)
    "flashlight" -> stringResource(R.string.tool_flashlight_desc)
    "media_button" -> stringResource(R.string.tool_media_button_desc)
    "location_get" -> stringResource(R.string.tool_location_get_desc)
    "device_state" -> stringResource(R.string.tool_device_state_desc)
    "voice_listen_start" -> stringResource(R.string.tool_voice_listen_start_desc)
    "voice_listen_stop" -> stringResource(R.string.tool_voice_listen_stop_desc)
    "memory_get" -> stringResource(R.string.tool_memory_get_desc)
    "memory_put" -> stringResource(R.string.tool_memory_put_desc)
    "memory_forget" -> stringResource(R.string.tool_memory_forget_desc)
    "skill_list" -> stringResource(R.string.tool_skill_list_desc)
    "skill_get" -> stringResource(R.string.tool_skill_get_desc)
    else -> stringResource(R.string.tool_unknown_desc)
}

@Composable
private fun permissionLabel(permission: String): String = when (permission) {
    android.Manifest.permission.CALL_PHONE -> stringResource(R.string.perm_call_phone)
    android.Manifest.permission.SEND_SMS -> stringResource(R.string.perm_send_sms)
    android.Manifest.permission.READ_CONTACTS -> stringResource(R.string.perm_read_contacts)
    android.Manifest.permission.READ_CALENDAR,
    android.Manifest.permission.WRITE_CALENDAR -> stringResource(R.string.perm_calendar)
    android.Manifest.permission.CAMERA -> stringResource(R.string.perm_camera)
    android.Manifest.permission.RECORD_AUDIO -> stringResource(R.string.perm_microphone)
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.ACCESS_COARSE_LOCATION -> stringResource(R.string.perm_location)
    else -> permission
}
