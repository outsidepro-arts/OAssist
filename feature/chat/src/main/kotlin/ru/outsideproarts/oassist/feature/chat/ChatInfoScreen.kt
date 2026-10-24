package ru.outsideproarts.oassist.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.model.aggregateUsage
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.R as UiR
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading
import ru.outsideproarts.oassist.core.ui.components.OAssistUsageDetails

/**
 * Окно «Информация о чате» (по тапу на заголовок, Telegram-стиль):
 * переименование сверху, статистика диалога (вход/выход/кэш/итог/стоимость)
 * и история вызовов инструментов по ходам. Открывается на месте чата —
 * состояние (в т.ч. несохранённые ходы) берётся из ChatScreen напрямую.
 */
@Composable
internal fun ChatInfoScreen(
    initialTitle: String,
    kind: ChatKind,
    turns: List<StoredTurn>,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backLabel = stringResource(R.string.chat_info_back)
    val title = stringResource(R.string.chat_info_title)
    val nameLabel = stringResource(R.string.chat_info_name_label)
    val saveLabel = stringResource(R.string.chat_info_name_save)
    val statsTitle = stringResource(R.string.chat_info_stats)
    val statsEmpty = stringResource(R.string.chat_info_stats_empty)
    val toolsTitle = stringResource(R.string.chat_info_tools)
    val toolsEmpty = stringResource(R.string.chat_info_tools_empty)

    // Поле живёт локально (снимок при открытии): правки модели-имени посреди
    // ввода не должны затирать текст пользователя.
    var name by remember { mutableStateOf(initialTitle) }
    val canSave = kind != ChatKind.ASSISTANT &&
        name.isNotBlank() &&
        name.trim() != initialTitle

    val usage = aggregateUsage(turns)
    val toolTurns = turns.mapIndexedNotNull { index, turn ->
        val tools = turn.work.filterIsInstance<WorkEvent.Tool>()
        tools.takeIf { it.isNotEmpty() }?.let { (index + 1) to it }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OAssistDimens.ScreenContentPadding)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = backLabel,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.SectionSpacing),
        ) {
            if (kind != ChatKind.ASSISTANT) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { value -> name = value.filter { it != '\n' } },
                        label = { Text(nameLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (canSave) {
                        TextButton(
                            onClick = { onRename(name.trim()) },
                            modifier = Modifier.oassistTouchTarget(),
                        ) {
                            Text(saveLabel)
                        }
                    }
                }
            }

            item {
                OAssistPanel(title = statsTitle, showTitle = true) {
                    if (usage == null && turns.isEmpty()) {
                        Text(
                            text = statsEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OAssistUsageDetails(usage = usage, turns = turns.size)
                    }
                }
            }

            item {
                OAssistPanel(title = toolsTitle, showTitle = true) {
                    if (toolTurns.isEmpty()) {
                        Text(
                            text = toolsEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing)) {
                            toolTurns.forEach { (turnNumber, tools) ->
                                OAssistSectionHeading(
                                    text = stringResource(R.string.chat_info_turn, turnNumber),
                                )
                                tools.forEach { tool ->
                                    val out = tool.output
                                    val line = if (out.isNullOrBlank()) {
                                        stringResource(UiR.string.msg_tool_call, tool.name)
                                    } else {
                                        stringResource(UiR.string.msg_tool_result, tool.name) +
                                            ": " + out.take(200)
                                    }
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (tool.isError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
