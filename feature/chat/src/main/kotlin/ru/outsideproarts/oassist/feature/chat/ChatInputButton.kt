package ru.outsideproarts.oassist.feature.chat

import android.os.SystemClock
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import ru.outsideproarts.oassist.core.a11y.oassistCustomAction
import ru.outsideproarts.oassist.core.a11y.oassistStateDescription
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.OAssistDimens

private const val MIN_HOLD_CAPTURE_MS = 500L

@Composable
fun ChatInputButton(
    icon: ImageVector,
    label: String,
    holdLabel: String,
    canStartHold: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Boolean,
    onHoldRelease: () -> Unit,
    onHoldCancel: (tooShort: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    holdEnabled: Boolean = true,
    voiceActionLabel: String? = null,
    voiceStateLabel: String? = null,
    nowMillis: () -> Long = SystemClock::uptimeMillis,
) {
    val interactions = remember { MutableInteractionSource() }
    var press by remember { mutableStateOf<PressInteraction.Press?>(null) }
    var heldPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
    var captureStartedAt by remember { mutableStateOf(0L) }
    var pointerPressed by remember { mutableStateOf(false) }
    val now by rememberUpdatedState(nowMillis)
    val release by rememberUpdatedState(onHoldRelease)
    val cancel by rememberUpdatedState(onHoldCancel)

    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> press = interaction
                is PressInteraction.Release -> {
                    if (press === interaction.press) press = null
                    if (heldPress === interaction.press) {
                        heldPress = null
                        if (now() - captureStartedAt >= MIN_HOLD_CAPTURE_MS) {
                            release()
                        } else {
                            cancel(true)
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    if (press === interaction.press) press = null
                    if (heldPress === interaction.press) {
                        heldPress = null
                        cancel(false)
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (heldPress != null) {
                heldPress = null
                cancel(false)
            }
        }
    }

    val voiceSemantics = voiceActionLabel?.let { action ->
        Modifier.oassistCustomAction(action) { onClick(); true }
    } ?: Modifier
    val stateSemantics = voiceStateLabel?.let { Modifier.oassistStateDescription(it) } ?: Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .oassistTouchTarget()
            .clip(CircleShape)
            .semantics {
                contentDescription = label
                if (holdEnabled) {
                    // Semantic activation cannot represent a physical hold and release.
                    onLongClick(label = voiceActionLabel ?: label) { onClick(); true }
                }
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = if (canStartHold) holdLabel else label,
                onLongClick = if (holdEnabled) {
                    {
                        val currentPress = press
                        if (!pointerPressed || currentPress == null || !canStartHold) {
                            onClick()
                        } else if (onHoldStart()) {
                            captureStartedAt = now()
                            heldPress = currentPress
                        }
                    }
                } else {
                    null
                },
            )
            .then(PointerCancellationElement(
                pressedChange = { pointerPressed = it },
                cancel = {
                    press = null
                    if (heldPress != null) {
                        heldPress = null
                        cancel(false)
                    }
                },
            ))
            .then(voiceSemantics)
            .then(stateSemantics),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(OAssistDimens.IconSize),
        )
    }
}

// This inner observer handles native cancellation before clickable finishes its press feedback.
private data class PointerCancellationElement(
    val pressedChange: (Boolean) -> Unit,
    val cancel: () -> Unit,
) : ModifierNodeElement<PointerCancellationNode>() {
    override fun create() = PointerCancellationNode(pressedChange, cancel)
    override fun update(node: PointerCancellationNode) {
        node.pressedChange = pressedChange
        node.cancel = cancel
    }
    override fun InspectorInfo.inspectableProperties() { name = "voicePointerLifecycle" }
}

private class PointerCancellationNode(
    var pressedChange: (Boolean) -> Unit,
    var cancel: () -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial) pressedChange(pointerEvent.changes.any { it.pressed })
    }
    override fun onCancelPointerInput() {
        pressedChange(false)
        cancel()
    }
}
