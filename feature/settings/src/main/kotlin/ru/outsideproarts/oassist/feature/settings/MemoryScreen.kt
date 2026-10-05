package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel
import ru.outsideproarts.oassist.core.ui.components.OAssistSectionHeading

/**
 * Память агента (`MEMORY.md`): редактор фактов + полное стирание.
 * Предупреждение про секреты — прямо под редактором: ключи/токены/пароли
 * живут только в SecretStore, в память им нельзя.
 */
@Composable
fun MemoryScreen(
    text: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.memory_title)
    var draft by remember { mutableStateOf<String?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (draft == null && text != null) draft = text
    }

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
            OAssistSectionHeading(text = title)
            OAssistPanel(title = title) {
                Text(
                    text = stringResource(R.string.memory_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.orEmpty(),
                    onValueChange = { draft = it },
                    label = { Text(title) },
                    minLines = 8,
                    enabled = draft != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        OutlinedButton(
            onClick = { draft?.let { onSave(it) } },
            enabled = draft != null,
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Text(stringResource(R.string.action_save))
        }
        OutlinedButton(
            onClick = { confirmingClear = true },
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Text(stringResource(R.string.memory_clear))
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Text(stringResource(R.string.action_back))
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.memory_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    draft = ""
                    confirmingClear = false
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
