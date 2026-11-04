package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.ui.R

/** Separate from the model answer and collapsed work panel; announced politely once shown. */
@Composable
fun ThinkingContextNotice(visible: Boolean) {
    if (visible) OAssistLiveStatus(stringResource(R.string.thinking_context_reduced))
}
