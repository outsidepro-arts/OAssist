package ru.outsideproarts.oassist.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Тема OAssist. Контраст AA (4.5:1 текст, 3:1 UI) — в обеих схемах.
 */
@Composable
fun OAssistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) OAssistDarkColors else OAssistLightColors,
        typography = OAssistTypography,
        content = content,
    )
}
