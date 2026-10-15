package ru.outsideproarts.oassist.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

/** Локализованная метка группы разрешений по её ключу. */
@Composable
fun toolGroupLabel(groupKey: String): String = when (groupKey) {
    ToolGroups.SERVICE -> stringResource(R.string.group_service)
    ToolGroups.UNKNOWN -> stringResource(R.string.group_unknown)
    else -> when {
        ToolGroups.isMcp(groupKey) -> stringResource(R.string.group_mcp)
        else -> {
            val category = ToolCategory.entries.firstOrNull {
                groupKey.startsWith("${it.name}.")
            } ?: return stringResource(R.string.group_unknown)
            val access = ToolGroups.accessOf(groupKey) ?: ToolAccess.WRITE
            val resId = groupLabelRes(category, access)
                ?: return stringResource(R.string.group_unknown)
            stringResource(resId)
        }
    }
}

private fun groupLabelRes(category: ToolCategory, access: ToolAccess): Int? = when (category) {
    ToolCategory.COMMUNICATION -> if (access == ToolAccess.WRITE) R.string.group_communication_write else null
    ToolCategory.PHONE_DATA -> if (access == ToolAccess.READ) {
        R.string.group_phone_data_read
    } else {
        R.string.group_phone_data_write
    }
    ToolCategory.CALL_LOG -> if (access == ToolAccess.READ) R.string.group_call_log_read else null
    ToolCategory.APP_DATA -> if (access == ToolAccess.READ) {
        R.string.group_app_data_read
    } else {
        R.string.group_app_data_write
    }
    ToolCategory.NOTIFICATIONS -> if (access == ToolAccess.READ) {
        R.string.group_notifications_read
    } else {
        R.string.group_notifications_write
    }
    ToolCategory.SCREEN_UI -> if (access == ToolAccess.READ) {
        R.string.group_screen_ui_read
    } else {
        R.string.group_screen_ui_write
    }
    ToolCategory.MICROPHONE -> if (access == ToolAccess.READ) R.string.group_microphone_read else null
    ToolCategory.GEOLOCATION -> if (access == ToolAccess.READ) R.string.group_geolocation_read else null
    ToolCategory.DEVICE -> if (access == ToolAccess.READ) {
        R.string.group_device_read
    } else {
        R.string.group_device_write
    }
    ToolCategory.WEB -> if (access == ToolAccess.READ) R.string.group_web_read else null
    ToolCategory.APPS_NAVIGATION -> if (access == ToolAccess.WRITE) {
        R.string.group_apps_navigation_write
    } else {
        R.string.group_apps_navigation_read
    }
    ToolCategory.PRIVILEGED -> if (access == ToolAccess.READ) {
        R.string.group_privileged_read
    } else {
        R.string.group_privileged_write
    }
    ToolCategory.MCP -> R.string.group_mcp
    ToolCategory.SERVICE -> R.string.group_service
    ToolCategory.OTHER -> null
}
