package ru.outsideproarts.oassist.core.agent

import android.app.NotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A synchronous API return confirms submission, not convergence of the global filter. */
internal fun sendDndRequest(
    enabled: Boolean,
    appRuleOnly: Boolean,
    hasAccess: () -> Boolean,
    setFilter: (Int) -> Unit,
    readFilter: () -> Int,
    text: (Int) -> String,
): ToolResult {
    val requested = if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE
        else NotificationManager.INTERRUPTION_FILTER_ALL
    try {
        if (!hasAccess()) return ToolResult(false, text(R.string.dnd_access_required))
        setFilter(requested)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A Binder failure does not prove the external operation was rolled back.
        return ToolResult(false, text(R.string.dnd_request_unconfirmed))
    }
    val observed = try {
        readFilter()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
    val filterName = when (observed) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        else -> "unknown"
    }
    return ToolResult(true, buildJsonObject {
        put("requestAccepted", true)
        put("requestedFilter", if (enabled) "none" else "all")
        put("observedGlobalFilter", filterName)
        put("observedGlobalFilterValue", observed)
        // Even a matching snapshot cannot establish which request caused the global state.
        put("globalOutcomeConfirmed", false)
        put("scope", if (appRuleOnly) "app_rule" else "global")
        put("message", text(R.string.dnd_request_submitted))
        if (appRuleOnly) put("limitation", text(R.string.dnd_app_rule_only))
        if (observed != requested) put("nextStep", text(R.string.dnd_check_settings))
    }.toString())
}
