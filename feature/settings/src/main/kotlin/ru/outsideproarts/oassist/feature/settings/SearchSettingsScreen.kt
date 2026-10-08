package ru.outsideproarts.oassist.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.a11y.oassistDropdownClassName
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.SearchEngine
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel

/**
 * Настройка поисковой системы для тула `web_search`.
 * Дропдаун канонический: menuAnchor(PrimaryNotEditable) + oassistDropdownClassName.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsScreen(
    engine: SearchEngine,
    onEngineChange: (SearchEngine) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_search_title)
    val label = stringResource(R.string.search_engine_label)
    val hint = stringResource(R.string.search_engine_hint)
    val backLabel = stringResource(R.string.action_back)
    var expanded by remember { mutableStateOf(false) }
    val options = SearchEngine.entries.map { it to engineLabel(it) }

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

        OAssistPanel(title = title) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = options.firstOrNull { it.first == engine }?.second ?: options.first().second,
                    onValueChange = {},
                    label = { Text(label) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .oassistDropdownClassName(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { (value, optionLabel) ->
                        DropdownMenuItem(
                            text = { Text(optionLabel) },
                            onClick = {
                                onEngineChange(value)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget(),
        ) {
            Text(backLabel)
        }
    }
}

@Composable
private fun engineLabel(engine: SearchEngine): String = when (engine) {
    SearchEngine.DUCKDUCKGO -> stringResource(R.string.search_engine_duckduckgo)
    SearchEngine.YANDEX -> stringResource(R.string.search_engine_yandex)
    SearchEngine.GOOGLE -> stringResource(R.string.search_engine_google)
    SearchEngine.BING -> stringResource(R.string.search_engine_bing)
    SearchEngine.MAILRU -> stringResource(R.string.search_engine_mailru)
    SearchEngine.ECOSIA -> stringResource(R.string.search_engine_ecosia)
    SearchEngine.BRAVE -> stringResource(R.string.search_engine_brave)
    SearchEngine.MOJEEK -> stringResource(R.string.search_engine_mojeek)
}