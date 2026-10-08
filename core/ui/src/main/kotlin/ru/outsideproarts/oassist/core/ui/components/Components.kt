package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Icon
import ru.outsideproarts.oassist.core.a11y.oassistCheckboxRow
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistMergedContent
import ru.outsideproarts.oassist.core.a11y.oassistSwitchRow
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
 * [showTitle] — рендерить ли заголовок визуально как Text (и тогда `paneTitle` не ставится —
 *   заголовок виден и читается как heading TalkBack);
 *   если [showTitle] = false, заголовок существует только как `paneTitle` для TalkBack
 *   (визуально заголовок НЕ отображается — избегаем дубля «paneTitle + Text(title)»).
 *   По умолчанию `false` — заголовок только в семантике.
 */
@Composable
fun OAssistPanel(
    title: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    showTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val stateMod = if (stateDescription != null) {
        Modifier.semantics { this.stateDescription = stateDescription }
    } else {
        Modifier
    }
    val paneMod = if (!showTitle) {
        Modifier.semantics { paneTitle = title }
    } else {
        Modifier
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(paneMod)
            .then(stateMod),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(OAssistDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
        ) {
            if (showTitle) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.oassistHeading(),
                )
            }
            content()
        }
    }
}

/**
 * Строка настроек: иконка + заголовок + опционально значение. Вся строка — один touch target.
 * Контент карточки мержится для TalkBack (один свайп = вся строка, не поле-в-поле).
 */
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
        .oassistMergedContent()
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
 * Канонический Switch-ряд (WCAG 4.1.2 / CVS Health SwitchControls.md).
 * - `Row` целиком становится фокусируемым узлом с `Role.Switch` (через `oassistSwitchRow`).
 * - Внутри `Text(label)` (без модификаторов) и `Switch(onCheckedChange = null, minimumInteractiveComponentSize)`.
 * - `stateDescription` объявляет состояние TalkBack («Включён» / «Выключен»).
 * - Жест = один тап по строке = переключение. Дополнительных custom-actions не требуется.
 */
@Composable
fun OAssistSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    stateLabelOn: String,
    stateLabelOff: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = if (checked) stateLabelOn else stateLabelOff
    Row(
        modifier = modifier
            .fillMaxWidth()
            .oassistTouchTarget()
            .oassistSwitchRow(checked = checked, enabled = enabled, onValueChange = onCheckedChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

/**
 * Канонический Checkbox-ряд (по образцу [OAssistSwitchRow], Role.Checkbox):
 * `Row` целиком фокусируем, TalkBack читает лейбл + состояние одним блоком.
 */
@Composable
fun OAssistCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    stateLabelOn: String,
    stateLabelOff: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = if (checked) stateLabelOn else stateLabelOff
    Row(
        modifier = modifier
            .fillMaxWidth()
            .oassistTouchTarget()
            .oassistCheckboxRow(checked = checked, enabled = enabled, onValueChange = onCheckedChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
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

/**
 * Мини-превью изображения (скриншоты, вложения). Контент, а не декорация —
 * contentDescription обязателен и приходит из строки локали модуля.
 */
@Composable
fun OAssistImageThumbnail(
    base64: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(base64) {
        runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}