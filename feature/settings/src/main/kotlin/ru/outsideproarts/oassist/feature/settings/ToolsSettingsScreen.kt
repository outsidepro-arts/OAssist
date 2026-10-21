package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.a11y.oassistDropdownClassName
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.ToolDescriptor
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.AskPreset
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSwitchRow
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistSettingRow
import ru.outsideproarts.oassist.core.ui.toolGroupLabel

/**
 * «Инструменты агента»: какие инструменты включены (группами по категориям)
 * и когда спрашивать (пресет + запомненные решения по категориям).
 * Подтверждения per-tool не настраиваются — всё решают группы разрешений.
 */
@Composable
fun ToolsSettingsScreen(
    tools: List<ToolDescriptor>,
    disabled: Set<String>,
    rememberedApprovals: Map<String, ApprovalState>,
    matchedPreset: AskPreset?,
    maxToolRounds: Int = ru.outsideproarts.oassist.core.model.ToolPreferences.DEFAULT_MAX_TOOL_ROUNDS,
    onToggleEnabled: (String, Boolean) -> Unit,
    onApplyPreset: (AskPreset) -> Unit,
    onResetGroup: (String) -> Unit,
    onMaxToolRoundsChange: (Int) -> Unit = {},
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
            val grouped = remember(tools) {
                tools.groupBy { it.groupKey }.toList()
            }
            grouped.forEach { (groupKey, groupTools) ->
                Text(
                    text = toolGroupLabel(groupKey),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistHeading(),
                )
                groupTools.forEach { descriptor ->
                    val enabled = descriptor.name !in disabled
                    ToolRow(
                        descriptor = descriptor,
                        enabled = enabled,
                        onToggleEnabled = { onToggleEnabled(descriptor.name, it) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing)) {
            OAssistSectionHeading(text = securitySection)
            OAssistPanel(title = securitySection) {
                PresetRow(
                    matchedPreset = matchedPreset,
                    onApplyPreset = onApplyPreset,
                )
                MaxToolRoundsRow(
                    maxToolRounds = maxToolRounds,
                    onChange = onMaxToolRoundsChange,
                )
                if (rememberedApprovals.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.approvals_remembered_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .oassistHeading(),
                    )
                    rememberedApprovals.toSortedMap().forEach { (groupKey, state) ->
                        RememberedGroupRow(
                            groupKey = groupKey,
                            state = state,
                            onReset = { onResetGroup(groupKey) },
                        )
                    }
                }
                OAssistSettingRow(
                    title = stringResource(R.string.approvals_reset),
                    subtitle = stringResource(R.string.approvals_reset_subtitle),
                    onClick = onResetApprovals,
                )
                OAssistSettingRow(
                    title = stringResource(R.string.tools_reset),
                    subtitle = stringResource(R.string.tools_reset_subtitle),
                    onClick = onResetTools,
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
    onToggleEnabled: (Boolean) -> Unit,
) {
    val title = toolTitle(descriptor.name)
    val description = toolDescription(descriptor.name)
    val enabledLabel = stringResource(R.string.tool_enabled)
    val onState = stringResource(R.string.state_enabled)
    val offState = stringResource(R.string.state_disabled)

    OAssistPanel(title = title, showTitle = true) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OAssistSwitchRow(
            checked = enabled,
            onCheckedChange = onToggleEnabled,
            label = enabledLabel,
            stateLabelOn = onState,
            stateLabelOff = offState,
        )
    }
}

@Composable
private fun RememberedGroupRow(
    groupKey: String,
    state: ApprovalState,
    onReset: () -> Unit,
) {
    val stateLabel = when (state) {
        ApprovalState.ASK -> stringResource(R.string.approval_state_ask)
        ApprovalState.ALLOW -> stringResource(R.string.approval_state_allow)
        ApprovalState.DENY -> stringResource(R.string.approval_state_deny)
    }
    OAssistSettingRow(
        title = toolGroupLabel(groupKey),
        subtitle = stateLabel,
        onClick = onReset,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetRow(
    matchedPreset: AskPreset?,
    onApplyPreset: (AskPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = presetLabel(matchedPreset)
    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.HairlineSpacing)) {
        Text(
            text = stringResource(R.string.tools_ask_preset_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.tools_ask_preset)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .oassistDropdownClassName(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                AskPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(presetLabel(preset)) },
                        onClick = {
                            expanded = false
                            onApplyPreset(preset)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun presetLabel(preset: AskPreset?): String = when (preset) {
    AskPreset.ASK_READ -> stringResource(R.string.tools_ask_preset_read)
    AskPreset.ASK_WRITE -> stringResource(R.string.tools_ask_preset_write)
    AskPreset.ASK_BOTH -> stringResource(R.string.tools_ask_preset_both)
    AskPreset.ALLOW_ALL -> stringResource(R.string.tools_ask_preset_allow_all)
    AskPreset.DENY_ALL -> stringResource(R.string.tools_ask_preset_deny_all)
    null -> stringResource(R.string.tools_ask_preset_manual)
}

@Composable
private fun toolTitle(name: String): String = when (name) {
    "app_list" -> stringResource(R.string.tool_app_list_title)
    "app_activities" -> stringResource(R.string.tool_app_activities_title)
    "app_launch" -> stringResource(R.string.tool_app_launch_title)
    "app_start_activity" -> stringResource(R.string.tool_app_start_activity_title)
    "web_open" -> stringResource(R.string.tool_web_open_title)
    "web_search" -> stringResource(R.string.tool_web_search_title)
    "alarm_set" -> stringResource(R.string.tool_alarm_set_title)
    "open_settings" -> stringResource(R.string.tool_open_settings_title)
    "call_place" -> stringResource(R.string.tool_call_place_title)
    "sms_send" -> stringResource(R.string.tool_sms_send_title)
    "contact_find" -> stringResource(R.string.tool_contact_find_title)
    "call_log_list" -> stringResource(R.string.tool_call_log_list_title)
    "calendar_add" -> stringResource(R.string.tool_calendar_add_title)
    "timer_set" -> stringResource(R.string.tool_timer_set_title)
    "device_info" -> stringResource(R.string.tool_device_info_title)
    "clipboard_copy" -> stringResource(R.string.tool_clipboard_copy_title)
    "ui_get_screen" -> stringResource(R.string.tool_ui_get_screen_title)
    "ui_screenshot" -> stringResource(R.string.tool_ui_screenshot_title)
    "ui_screenshot_region" -> stringResource(R.string.tool_ui_screenshot_region_title)
    "ui_screenshot_node" -> stringResource(R.string.tool_ui_screenshot_node_title)
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
    "shell_exec" -> stringResource(R.string.tool_shell_exec_title)
    "shell_get_setting" -> stringResource(R.string.tool_shell_get_setting_title)
    "shell_put_setting" -> stringResource(R.string.tool_shell_put_setting_title)
    "notify_reply" -> stringResource(R.string.tool_notify_reply_title)
    "volume_set" -> stringResource(R.string.tool_volume_set_title)
    "dnd_set" -> stringResource(R.string.tool_dnd_set_title)
    "brightness_set" -> stringResource(R.string.tool_brightness_set_title)
    "bye" -> stringResource(R.string.tool_bye_title)
    else -> name
}

@Composable
private fun toolDescription(name: String): String = when (name) {
    "app_list" -> stringResource(R.string.tool_app_list_desc)
    "app_activities" -> stringResource(R.string.tool_app_activities_desc)
    "app_launch" -> stringResource(R.string.tool_app_launch_desc)
    "app_start_activity" -> stringResource(R.string.tool_app_start_activity_desc)
    "web_open" -> stringResource(R.string.tool_web_open_desc)
    "web_search" -> stringResource(R.string.tool_web_search_desc)
    "alarm_set" -> stringResource(R.string.tool_alarm_set_desc)
    "open_settings" -> stringResource(R.string.tool_open_settings_desc)
    "call_place" -> stringResource(R.string.tool_call_place_desc)
    "sms_send" -> stringResource(R.string.tool_sms_send_desc)
    "contact_find" -> stringResource(R.string.tool_contact_find_desc)
    "call_log_list" -> stringResource(R.string.tool_call_log_list_desc)
    "calendar_add" -> stringResource(R.string.tool_calendar_add_desc)
    "timer_set" -> stringResource(R.string.tool_timer_set_desc)
    "device_info" -> stringResource(R.string.tool_device_info_desc)
    "clipboard_copy" -> stringResource(R.string.tool_clipboard_copy_desc)
    "ui_get_screen" -> stringResource(R.string.tool_ui_get_screen_desc)
    "ui_screenshot" -> stringResource(R.string.tool_ui_screenshot_desc)
    "ui_screenshot_region" -> stringResource(R.string.tool_ui_screenshot_region_desc)
    "ui_screenshot_node" -> stringResource(R.string.tool_ui_screenshot_node_desc)
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
    "shell_exec" -> stringResource(R.string.tool_shell_exec_desc)
    "shell_get_setting" -> stringResource(R.string.tool_shell_get_setting_desc)
    "shell_put_setting" -> stringResource(R.string.tool_shell_put_setting_desc)
    "notify_reply" -> stringResource(R.string.tool_notify_reply_desc)
    "volume_set" -> stringResource(R.string.tool_volume_set_desc)
    "dnd_set" -> stringResource(R.string.tool_dnd_set_desc)
    "brightness_set" -> stringResource(R.string.tool_brightness_set_desc)
    "bye" -> stringResource(R.string.tool_bye_desc)
    else -> stringResource(R.string.tool_unknown_desc)
}

private val MAX_TOOL_ROUND_OPTIONS = listOf(0, 5, 10, 15, 20, 30)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxToolRoundsRow(
    maxToolRounds: Int,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.tools_max_rounds_none)
    Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.HairlineSpacing)) {
        Text(
            text = stringResource(R.string.tools_max_rounds_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = if (maxToolRounds <= 0) noneLabel else maxToolRounds.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.tools_max_rounds)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .oassistDropdownClassName(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                MAX_TOOL_ROUND_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option <= 0) noneLabel else option.toString()) },
                        onClick = {
                            expanded = false
                            onChange(option)
                        },
                    )
                }
            }
        }
    }
}
