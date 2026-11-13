package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.ToolPreferences
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ModelClient

internal fun AgentLoop(
    modelClient: ModelClient,
    toolRegistry: ToolRegistry? = null,
    approvalController: ApprovalController = ApprovalController(),
    approvalPolicy: ApprovalPolicy = InMemoryApprovalPolicy(),
    events: AgentEvents? = null,
    visionEnabled: Boolean = true,
    reasoningEnabled: Boolean = false,
    maxToolRounds: Int = ToolPreferences.DEFAULT_MAX_TOOL_ROUNDS,
    sessionScope: String = "CHAT",
    permissionRequester: PermissionRequester? = null,
    screenLocked: () -> Boolean = { false },
    fallbackFarewell: String? = null,
    streamContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    callbackContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
): AgentLoop = AgentLoop(
    modelClient, toolRegistry, approvalController, approvalPolicy, events, visionEnabled,
    reasoningEnabled, maxToolRounds, sessionScope, permissionRequester, screenLocked,
    fallbackFarewell, Diagnostics, streamContext, callbackContext,
)

internal fun ToolRegistry(tools: List<Tool>, disabled: Set<String> = emptySet()): ToolRegistry =
    ToolRegistry(tools, disabled, Diagnostics)
