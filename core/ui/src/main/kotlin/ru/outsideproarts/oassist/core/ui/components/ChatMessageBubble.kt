package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.StoredTool
import ru.outsideproarts.oassist.core.ui.R

/**
 * Пузырь реплики (чат, шаринг, история диалога): роль, сворачиваемое полотно
 * «Ход работы» (мысль + вызовы + статистика) и текст. Голосовой ход —
 * плейсхолдер с длительностью, байты не показываются. Видимый текст не live
 * region — озвучка идёт каналом речи, повтор действием «Озвучить ответ».
 */
@Composable
fun ChatMessageBubble(
    isUser: Boolean,
    answer: String,
    audio: AudioPart? = null,
    thought: String = "",
    tools: List<StoredTool> = emptyList(),
    usage: ProviderUsage? = null,
    onSpeak: (() -> Unit)? = null,
    speakLabel: String,
    modifier: Modifier = Modifier,
) {
    val roleLabel = if (isUser) {
        stringResource(R.string.msg_role_user)
    } else {
        stringResource(R.string.msg_role_assistant)
    }
    val bodyText = audio?.let { stringResource(R.string.msg_voice_message, it.durationSec) }
        ?: answer.ifEmpty { "…" }

    val speakModifier = if (onSpeak != null) {
        Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(speakLabel) { onSpeak(); true },
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = roleLabel },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isUser && (thought.isNotBlank() || tools.isNotEmpty() || usage != null)) {
            WorkBlock(thought = thought, tools = tools, usage = usage)
        }
        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = speakModifier,
        )
    }
}

/**
 * Свёрнутое по умолчанию полотно мысли и вызовов инструментов.
 * Мысль и вызовы объединены в одну сущность (разворачиваются вместе).
 */
@Composable
private fun WorkBlock(
    thought: String,
    tools: List<StoredTool>,
    usage: ProviderUsage?,
) {
    val title = stringResource(R.string.msg_work_title)
    val expandedState = stringResource(R.string.msg_expanded)
    val collapsedState = stringResource(R.string.msg_collapsed)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) expandedState else collapsedState
    val toggleLabel = "$title. $state"
    val actionLabel = if (expanded) {
        stringResource(R.string.msg_collapse)
    } else {
        stringResource(R.string.msg_expand)
    }

    OAssistPanel(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .semantics {
                    stateDescription = state
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            expanded = !expanded
                            true
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = actionLabel },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            OAssistUsageLine(usage = usage)
            if (thought.isNotBlank()) {
                Text(
                    text = thought.take(2000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tools.takeLast(20).forEach { tool ->
                val out = tool.output
                val line = if (out.isNullOrBlank()) {
                    stringResource(R.string.msg_tool_call, tool.name)
                } else {
                    stringResource(R.string.msg_tool_result, tool.name) + ": " + out.take(500)
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tool.images.isNotEmpty()) {
                    val imageDesc = stringResource(R.string.msg_image_attached)
                    tool.images.forEach { img ->
                        OAssistImageThumbnail(
                            base64 = img.imageBase64,
                            contentDescription = imageDesc,
                        )
                    }
                }
            }
        }
    }
}
