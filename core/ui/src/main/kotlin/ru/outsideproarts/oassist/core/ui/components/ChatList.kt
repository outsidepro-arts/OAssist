package ru.outsideproarts.oassist.core.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.outsideproarts.oassist.core.a11y.oassistCustomAction
import ru.outsideproarts.oassist.core.a11y.oassistMergedContent
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.ChatSummary
import ru.outsideproarts.oassist.core.model.FuzzyMatcher
import ru.outsideproarts.oassist.core.ui.R

/**
 * Строка списка чатов: тап — открыть, долгое удержание — переименовать,
 * смахивание — удалить (с подтверждением). Каждому жесту — видимая альтернатива
 * (меню «⋮») и CustomAccessibilityAction. Null у действия — оно недоступно
 * (закреплённая запись ассистента: не переименовывается и не удаляется).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatListItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()
    androidx.compose.runtime.LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            onDelete?.invoke()
        }
    }
    val renameLabel = stringResource(R.string.chats_action_rename)
    val deleteLabel = stringResource(R.string.chats_action_delete)

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = onDelete != null,
        gesturesEnabled = onDelete != null,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rowActions = Modifier
                .weight(1f)
                .oassistTouchTarget()
                .combinedClickable(onClick = onClick, onLongClick = onRename)
                .oassistMergedContent()
                .let { base ->
                    if (onRename != null) {
                        base.oassistCustomAction(label = renameLabel) {
                            onRename()
                            true
                        }
                    } else {
                        base
                    }
                }
                .let { base ->
                    if (onDelete != null) {
                        base.oassistCustomAction(label = deleteLabel) {
                            onDelete()
                            true
                        }
                    } else {
                        base
                    }
                }
            Column(
                modifier = rowActions.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onRename != null || onDelete != null) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.oassistTouchTarget(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.chats_menu),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text(renameLabel) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(deleteLabel) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Общий список чатов (шторка чата, шаринг): поиск fuzzy, «Новый чат»,
 * закреплённая запись ассистента (не участвует в поиске и удалении),
 * переименование/удаление с подтверждением.
 */
@Composable
fun ChatPickerList(
    chats: List<ChatSummary>,
    onOpenChat: (ChatSummary) -> Unit,
    onNewChat: () -> Unit,
    onRenameChat: (ChatSummary, String) -> Unit,
    onDeleteChat: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
    includeAssistant: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSummary?>(null) }
    val assistantTitle = stringResource(R.string.chats_assistant_title)
    val newChatLabel = stringResource(R.string.chats_new)
    val emptyLabel = stringResource(R.string.chats_empty)
    val noMatchesLabel = stringResource(R.string.chats_no_matches)
    val titleOf: (ChatSummary) -> String = {
        if (it.kind == ChatKind.ASSISTANT) assistantTitle else it.title
    }

    val assistant = chats.firstOrNull { it.kind == ChatKind.ASSISTANT }
        .takeIf { includeAssistant }
    val regular = chats.filter { it.kind != ChatKind.ASSISTANT }
    val filtered = FuzzyMatcher.rank(query, regular) { titleOf(it) }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.chats_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OAssistSettingRow(title = newChatLabel, onClick = onNewChat)
        if (assistant != null) {
            ChatListItem(
                title = titleOf(assistant),
                subtitle = formatDate(assistant.updatedAt),
                onClick = { onOpenChat(assistant) },
                onRename = null,
                onDelete = null,
            )
        }
        if (filtered.isEmpty()) {
            Text(
                text = if (regular.isEmpty()) emptyLabel else noMatchesLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { chat ->
                    ChatListItem(
                        title = titleOf(chat),
                        subtitle = formatDate(chat.updatedAt),
                        onClick = { onOpenChat(chat) },
                        onRename = { renameTarget = chat },
                        onDelete = { deleteTarget = chat },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        ChatRenameDialog(
            initialName = titleOf(target),
            onConfirm = { name ->
                renameTarget = null
                if (name.isNotBlank()) onRenameChat(target, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        ChatDeleteDialog(
            chatTitle = titleOf(target),
            onConfirm = {
                deleteTarget = null
                onDeleteChat(target)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

private fun formatDate(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(timestamp).toString()

/** Диалог переименования чата. Пустое имя не сохраняется. */
@Composable
fun ChatRenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chats_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.chats_rename_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.oassistTouchTarget(),
            ) {
                Text(stringResource(R.string.chats_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.oassistTouchTarget()) {
                Text(stringResource(R.string.chats_cancel))
            }
        },
    )
}

/** Подтверждение удаления чата (свайп/меню/CustomAction — единый финал). */
@Composable
fun ChatDeleteDialog(
    chatTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chats_delete_title)) },
        text = { Text(stringResource(R.string.chats_delete_message, chatTitle)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.oassistTouchTarget()) {
                Text(stringResource(R.string.chats_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.oassistTouchTarget()) {
                Text(stringResource(R.string.chats_cancel))
            }
        },
    )
}

/**
 * Защита несохранённого диалога при смене: сохранить и продолжить, продолжить
 * без сохранения или отменить. Dismiss = отмена (ничего не теряем).
 */
@Composable
fun ChatUnsavedDialog(
    onSaveAndContinue: () -> Unit,
    onContinueWithoutSaving: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chats_unsaved_title)) },
        text = { Text(stringResource(R.string.chats_unsaved_message)) },
        confirmButton = {
            TextButton(onClick = onSaveAndContinue, modifier = Modifier.oassistTouchTarget()) {
                Text(stringResource(R.string.chats_unsaved_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onContinueWithoutSaving, modifier = Modifier.oassistTouchTarget()) {
                    Text(stringResource(R.string.chats_unsaved_continue))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.oassistTouchTarget()) {
                    Text(stringResource(R.string.chats_cancel))
                }
            }
        },
    )
}
