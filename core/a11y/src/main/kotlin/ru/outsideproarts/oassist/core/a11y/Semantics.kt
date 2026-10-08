package ru.outsideproarts.oassist.core.a11y

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.accessibilityClassName
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp

object A11y {
    const val MIN_TOUCH_TARGET = 48
}

/**
 * Touch target ≥ 48dp. Обязателен для всего интерактивного.
 */
fun Modifier.oassistTouchTarget(): Modifier =
    defaultMinSize(minWidth = A11y.MIN_TOUCH_TARGET.dp, minHeight = A11y.MIN_TOUCH_TARGET.dp)

/**
 * Заголовок секции: TalkBack выделяет в навигации по заголовкам.
 */
fun Modifier.oassistHeading(): Modifier = semantics { heading() }

/**
 * Имя панели/оверлея. Для оверлея ассистента — обязательно.
 */
fun Modifier.oassistPaneTitle(title: String): Modifier = semantics { paneTitle = title }

/**
 * Динамический контент. Политичный вариант — ждать паузу речи.
 * Видимый текст стрима живым регионом делать НЕЛЬЗЯ (TalkBack перечитает всё):
 * живой регион — только на announcer-узле с последней фразой.
 */
fun Modifier.oassistLiveRegion(polite: Boolean = true): Modifier = semantics {
    liveRegion = if (polite) LiveRegionMode.Polite else LiveRegionMode.Assertive
}

fun Modifier.oassistStateDescription(description: String): Modifier =
    semantics { stateDescription = description }

fun Modifier.oassistRole(role: Role): Modifier = semantics { this.role = role }

/**
 * Дополнительное действие в меню TalkBack.
 * Жест никогда не должен быть единственным путём к действию.
 */
fun Modifier.oassistCustomAction(label: String, action: () -> Boolean): Modifier =
    oassistCustomActions(listOf(label to action))

fun Modifier.oassistCustomActions(actions: List<Pair<String, () -> Boolean>>): Modifier =
    semantics {
        customActions = actions.map { (label, act) -> CustomAccessibilityAction(label, act) }
    }

/** Явный порядок чтения, когда Compose-порядок спорит с визуальным. */
fun Modifier.oassistTraversalIndex(index: Float): Modifier = semantics { traversalIndex = index }

/**
 * Чистый выпадающий список для TalkBack.
 * `OutlinedTextField(readOnly=true)` внутри `ExposedDropdownMenuBox` остаётся
 * `EditText`-классом → TalkBack читает «Окно редактирования» + роль «Выпадающий список».
 * `accessibilityClassName = "android.widget.Spinner"` перезаписывает класс
 * → TalkBack читает чистое «Выпадающий список».
 */
fun Modifier.oassistDropdownClassName(): Modifier = semantics {
    accessibilityClassName = "android.widget.Spinner"
}

/**
 * Канонический Switch-ряд (WCAG 4.1.2, CVS Health SwitchControls.md).
 * Обёртка над `toggleable(role = Role.Switch)` — оборачивающий `Row` становится
 * единым фокусируемым узлом: TalkBack читает лейбл + состояние + Switch одним блоком.
 * Внутри Switch `onCheckedChange` обнуляется.
 */
fun Modifier.oassistSwitchRow(
    checked: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = this.toggleable(
    value = checked,
    enabled = enabled,
    role = Role.Switch,
    onValueChange = onValueChange,
)

/**
 * Канонический Checkbox-ряд (по образцу [oassistSwitchRow], Role.Checkbox).
 * `Row` целиком — единый фокусируемый узел: TalkBack читает лейбл + состояние
 * («Отмечено»/«Не отмечено») одним блоком. Внутри Checkbox `onCheckedChange` обнуляется.
 */
fun Modifier.oassistCheckboxRow(
    checked: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = this.toggleable(
    value = checked,
    enabled = enabled,
    role = Role.Checkbox,
    onValueChange = onValueChange,
)

/**
 * Группировка содержимого карточки для TalkBack (CVS Health ContentGrouping.md).
 * `mergeDescendants = true` — TalkBack читает всю карточку одним блоком;
 * один свайп = переход к следующей карточке. Визуально не меняется.
 */
fun Modifier.oassistMergedContent(): Modifier =
    semantics(mergeDescendants = true) {}

/**
 * Канонический Slider (WCAG 2.5.3 / 2.1.1 / 4.1.2, CVS Health SliderAndRangeSliderControls.md).
 * - contentDescription дублирует визуальный label (Label-in-Name);
 * - stateDescription объявляет текущее значение;
 * - liveRegion Polite — TalkBack читает изменение, даже если фокус не на слайдере;
 * - onKeyEvent — D-PAD влево/вправо двигают значение (BT-клавиатура и внешний D-pad).
 *
 * [stepIncrement] — шаг одной клавишной операции.
 */
fun Modifier.oassistSlider(
    label: String,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    stepIncrement: Float,
    currentValue: () -> Float,
): Modifier = this
    .semantics {
        contentDescription = label
        stateDescription = valueText
        liveRegion = LiveRegionMode.Polite
    }
    .onKeyEvent { keyEvent ->
        if (keyEvent.type != KeyEventType.KeyUp) return@onKeyEvent false
        val current = currentValue()
        val next = when (keyEvent.key) {
            Key.DirectionLeft -> current - stepIncrement
            Key.DirectionRight -> current + stepIncrement
            else -> return@onKeyEvent false
        }
        if (range.contains(next)) {
            onValueChange(next)
            true
        } else {
            false
        }
    }
