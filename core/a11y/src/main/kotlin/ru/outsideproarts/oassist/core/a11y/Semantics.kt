package ru.outsideproarts.oassist.core.a11y

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
