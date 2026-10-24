package ru.outsideproarts.oassist.feature.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ru.outsideproarts.oassist.core.a11y.AnnouncerHost
import ru.outsideproarts.oassist.core.a11y.FlowAnnouncer
import ru.outsideproarts.oassist.core.a11y.oassistCustomAction
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistStateDescription
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistLiveStatus
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel

/**
 * Компактная нижняя панель оверлея ассистента: строка ввода + статус; разворот —
 * полный ответ и «Ход работы». Свайп вниз по шапке закрывает (альтернатива — кнопка
 * и CustomAccessibilityAction).
 */
@Composable
fun AssistPill(
    answer: String,
    work: List<ru.outsideproarts.oassist.core.model.WorkEvent>,
    usage: ru.outsideproarts.oassist.core.model.ProviderUsage? = null,
    usageTotals: kotlinx.coroutines.flow.StateFlow<ru.outsideproarts.oassist.core.model.UsageTotalsState>? = null,
    statusHint: String?,
    listening: Boolean,
    sttPreview: String,
    isStreaming: Boolean,
    expanded: Boolean,
    announcer: FlowAnnouncer?,
    onExpandedChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onMicToggle: () -> Unit,
    onClose: () -> Unit,
    onSpeakAnswer: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.assistant_title)
    val askLabel = stringResource(R.string.assistant_ask)
    val closeLabel = stringResource(R.string.assistant_close)
    val micLabel = stringResource(R.string.assistant_mic)
    val micStateOn = stringResource(R.string.assistant_mic_state_on)
    val listeningLabel = stringResource(R.string.assistant_listening)
    val idleLabel = stringResource(R.string.assistant_mic_idle)
    val thinkingLabel = stringResource(R.string.assistant_pill_thinking)
    val speakLabel = stringResource(R.string.assistant_speak_answer)
    val expandLabel = stringResource(R.string.assistant_action_expand)
    val collapseLabel = stringResource(R.string.assistant_action_collapse)

    var question by remember { mutableStateOf(TextFieldValue("")) }
    var dragAccum by remember { mutableStateOf(0f) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(OAssistDimens.ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                dragAccum += dragAmount
                                if (dragAccum > size.height / 2f) onClose()
                            },
                        )
                    }
                    .oassistCustomAction(closeLabel) { onClose(); true },
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
                    onClick = { onExpandedChange(!expanded) },
                    modifier = Modifier
                        .oassistTouchTarget()
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(if (expanded) collapseLabel else expandLabel) {
                                    onExpandedChange(!expanded)
                                    true
                                },
                            )
                        },
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) collapseLabel else expandLabel,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = closeLabel,
                    )
                }
            }

            val status = when {
                statusHint != null -> statusHint
                listening && sttPreview.isNotBlank() -> "$listeningLabel $sttPreview"
                listening -> listeningLabel
                isStreaming -> thinkingLabel
                else -> null
            }
            if (status != null) {
                OAssistLiveStatus(
                    text = status,
                    assertive = statusHint != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text(askLabel) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onInputFocusChanged(it.isFocused) }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                                keyboardController?.show()
                            }
                        },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
                IconButton(
                    onClick = onMicToggle,
                    modifier = Modifier
                        .oassistTouchTarget()
                        .oassistStateDescription(if (listening) micStateOn else idleLabel),
                ) {
                    Icon(
                        imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = micLabel,
                    )
                }
                IconButton(
                    onClick = {
                        if (question.text.isNotBlank() && !isStreaming) {
                            onSend(question.text.trim())
                            question = TextFieldValue("")
                            focusManager.clearFocus()
                        }
                    },
                    enabled = question.text.isNotBlank() && !isStreaming,
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = askLabel,
                    )
                }
            }

            if (expanded) {
                val emptyTotals = remember {
                    kotlinx.coroutines.flow.MutableStateFlow(ru.outsideproarts.oassist.core.model.UsageTotalsState())
                }
                val totalsState by (usageTotals ?: emptyTotals).collectAsState()
                OAssistPanel(title = stringResource(R.string.assistant_response), showTitle = true) {
                    // Статистика живёт внутри «Хода работы» (сворачиваемо), не отдельной строкой.
                    if (work.isNotEmpty() || usage != null) {
                        AssistWorkBlock(
                            work = work,
                            usage = usage,
                            totals = totalsState,
                            isStreaming = isStreaming,
                        )
                    }
                    ru.outsideproarts.oassist.core.ui.markdown.MarkdownText(
                        text = answer.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(speakLabel) {
                                    onSpeakAnswer()
                                    true
                                },
                            )
                        },
                    )
                }
            } else if (answer.isNotBlank()) {
                Text(
                    text = answer.take(120),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget()
                        .oassistCustomAction(expandLabel) {
                            onExpandedChange(true)
                            true
                        },
                )
            }

            if (announcer != null) {
                AnnouncerHost(announcer = announcer)
            }
        }
    }
}

@Composable
private fun AssistWorkBlock(
    work: List<ru.outsideproarts.oassist.core.model.WorkEvent>,
    usage: ru.outsideproarts.oassist.core.model.ProviderUsage?,
    totals: ru.outsideproarts.oassist.core.model.UsageTotalsState,
    isStreaming: Boolean,
) {
    val title = stringResource(R.string.assistant_work_title)
    var expanded by remember { mutableStateOf(isStreaming) }
    // Развёрнут, пока агент думает; по Finish сворачивается (ручной тогл сохраняется).
    androidx.compose.runtime.LaunchedEffect(isStreaming) { expanded = isStreaming }
    val state = if (expanded) {
        stringResource(R.string.assistant_work_expanded)
    } else {
        stringResource(R.string.assistant_work_collapsed)
    }
    OAssistPanel(title = title) {
        // Шапка — один сливной узел TalkBack (клик по Row схлопывает детей),
        // без пустых обёрток: «Ход работы, Свёрнут, кнопка».
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .oassistHeading()
                .oassistStateDescription(state)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("$title. $state") {
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
            work.takeLast(40).forEach { event ->
                when (event) {
                    is ru.outsideproarts.oassist.core.model.WorkEvent.Thought -> {
                        ru.outsideproarts.oassist.core.ui.markdown.MarkdownText(
                            text = event.text.take(4000),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ru.outsideproarts.oassist.core.model.WorkEvent.Tool -> {
                        Text(
                            text = event.name + (event.output?.take(500)?.let { ": $it" } ?: "…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (event.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (event.images.isNotEmpty()) {
                            val imageDesc = stringResource(R.string.assistant_image_attached)
                            event.images.forEach { img ->
                                ru.outsideproarts.oassist.core.ui.components.OAssistImageThumbnail(
                                    base64 = img.imageBase64,
                                    contentDescription = imageDesc,
                                )
                            }
                        }
                    }
                }
            }
            // Статистика — последней строкой полотна.
            ru.outsideproarts.oassist.core.ui.components.OAssistUsageLine(usage = usage)
            ru.outsideproarts.oassist.core.ui.components.OAssistUsageTotalsLine(totals = totals)
        }
    }
}
