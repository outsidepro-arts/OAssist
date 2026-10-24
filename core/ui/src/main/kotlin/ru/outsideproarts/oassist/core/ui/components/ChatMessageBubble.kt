package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.ui.R
import ru.outsideproarts.oassist.core.ui.markdown.MarkdownText

/** Потолок записей полотна в пузыре — длинные цепочки не раздувают композицию. */
private const val MAX_WORK_EVENTS = 40
private const val MAX_THOUGHT_CHARS = 4000
private const val MAX_TOOL_OUTPUT_CHARS = 500

/**
 * Пузырь реплики (чат, шаринг, история диалога): роль, сворачиваемое полотно
 * «Ход работы» в стиле DeepSeek (мыслевые блоки и вызовы строго в хронологии,
 * статистика — последней строкой) и текст. Голосовой ход — плейсхолдер с
 * длительностью, байты не показываются. Видимый текст не live region —
 * озвучка идёт каналом речи, повтор действием «Озвучить ответ».
 */
@Composable
fun ChatMessageBubble(
    isUser: Boolean,
    answer: String,
    audio: AudioPart? = null,
    work: List<WorkEvent> = emptyList(),
    usage: ProviderUsage? = null,
    /** Ход ещё идёт: полотно развёрнуто, по Finish свернётся. */
    isStreaming: Boolean = false,
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
        if (!isUser && (work.isNotEmpty() || usage != null)) {
            WorkBlock(work = work, usage = usage, isStreaming = isStreaming)
        }
        if (!isUser) {
            MarkdownText(
                text = bodyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = speakModifier,
            )
        } else {
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = speakModifier,
            )
        }
    }
}

/**
 * Полотно «Ход работы» (DeepSeek-стиль): блоки размышлений и вызовы тулов
 * идут последовательно в хронологии появления; токены — последней строкой.
 * Развёрнуто, пока агент думает ([isStreaming]); по готовности ответа
 * сворачивается (ручной тогл при этом сохраняется — эффект срабатывает
 * только на смене флага).
 */
@Composable
private fun WorkBlock(
    work: List<WorkEvent>,
    usage: ProviderUsage?,
    isStreaming: Boolean,
) {
    val title = stringResource(R.string.msg_work_title)
    val expandedState = stringResource(R.string.msg_expanded)
    val collapsedState = stringResource(R.string.msg_collapsed)
    var expanded by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) { expanded = isStreaming }
    val state = if (expanded) expandedState else collapsedState
    val toggleLabel = "$title. $state"

    OAssistPanel(title = title) {
        // Шапка — один сливной узел TalkBack (клик по Row схлопывает детей):
        // «Ход работы, Свёрнут, кнопка». Раньше строка-обёртка жила без имени
        // (пустой узел со статусом), имя и кнопка — отдельными детьми.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .oassistHeading()
                .semantics {
                    stateDescription = state
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            expanded = !expanded
                            true
                        },
                    )
                }
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            work.takeLast(MAX_WORK_EVENTS).forEach { event ->
                when (event) {
                    is WorkEvent.Thought -> MarkdownText(
                        text = event.text.take(MAX_THOUGHT_CHARS),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is WorkEvent.Tool -> {
                        val out = event.output
                        val line = if (out.isNullOrBlank()) {
                            stringResource(R.string.msg_tool_call, event.name)
                        } else {
                            stringResource(R.string.msg_tool_result, event.name) +
                                ": " + out.take(MAX_TOOL_OUTPUT_CHARS)
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (event.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (event.images.isNotEmpty()) {
                            val imageDesc = stringResource(R.string.msg_image_attached)
                            event.images.forEach { img ->
                                OAssistImageThumbnail(
                                    base64 = img.imageBase64,
                                    contentDescription = imageDesc,
                                )
                            }
                        }
                    }
                }
            }
            // Статистика хода — последней строкой полотна.
            OAssistUsageLine(usage = usage)
        }
    }
}
