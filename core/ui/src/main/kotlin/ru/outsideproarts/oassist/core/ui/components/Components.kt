package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Icon
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.OAssistDimens

/** Заголовок секции. TalkBack: навигация по заголовкам. */
@Composable
fun OAssistSectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = OAssistDimens.ItemSpacing)
            .oassistHeading(),
    )
}

/**
 * Карточка-панель с именем для TalkBack.
 * [stateDescription] — например, «выбрано» / «не выбрано».
 */
@Composable
fun OAssistPanel(
    title: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val stateMod = if (stateDescription != null) {
        Modifier.semantics { this.stateDescription = stateDescription }
    } else {
        Modifier
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = title }
            .then(stateMod),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(OAssistDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
            content = content,
        )
    }
}

/** Строка настроек: иконка + заголовок + опционально значение. Вся строка — один touch target. */
@Composable
fun OAssistSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .fillMaxWidth()
        .oassistTouchTarget()
        .padding(vertical = OAssistDimens.OptionRowSpacing, horizontal = OAssistDimens.ScreenPadding)
    val clickableMod = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = base.then(clickableMod),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.ScreenPadding),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(OAssistDimens.IconSize),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.HairlineSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Статус, объявляемый экранным чтецу при изменении (LiveRegionMode.Polite).
 * Подходит для транзиентных статусов, не для накапливающегося текста.
 */
@Composable
fun OAssistLiveStatus(
    text: String,
    modifier: Modifier = Modifier,
    assertive: Boolean = false,
) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .padding(horizontal = OAssistDimens.ScreenPadding, vertical = OAssistDimens.DenseSpacing),
    )
}
