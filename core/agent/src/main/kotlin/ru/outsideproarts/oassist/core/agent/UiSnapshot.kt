package ru.outsideproarts.oassist.core.agent

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Окна, которые не должны попадать в снимок интерфейса для агента: клавиатура,
 * декор экранных чтецов и **наш собственный оверлей** (панель ассистента мешает
 * видеть и трогать приложение под собой). Activity-окна собственного пакета
 * остаются — агент может работать и с нашим UI.
 */
fun isIgnorableWindow(
    windowPackage: String?,
    windowType: Int,
    ownPackage: String,
): Boolean {
    if (windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
    if (windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return true
    return windowPackage == ownPackage && windowType != AccessibilityWindowInfo.TYPE_APPLICATION
}

/** Состояние окна для выбора цели снимка (без Android-типов в логике — тестируется на JVM). */
data class UiWindowState(
    val packageName: String?,
    val type: Int,
    val active: Boolean,
    val focused: Boolean,
)

/**
 * Выбор окна для снимка интерфейса: активное/фокусное (кроме игнорируемых), иначе — верхнее
 * окно приложения. Системная хромировка (статус-бар/навбар) целью не становится вовсе:
 * её слой выше приложения, и по одному слою она всегда выигрывала бы. null — вызывающий
 * фолбэчит на активное окно.
 */
fun pickWindowIndex(windows: List<UiWindowState>, ownPackage: String): Int? {
    val candidates = windows.indices.filterNot {
        isIgnorableWindow(windows[it].packageName, windows[it].type, ownPackage)
    }
    return candidates.firstOrNull { windows[it].active || windows[it].focused }
        ?: candidates.firstOrNull { windows[it].type == AccessibilityWindowInfo.TYPE_APPLICATION }
}

/**
 * Один узел снимка экрана. Индексы стабильны в пределах одного снимка;
 * инструменты перечитывают снимок перед действием (узлы протухают).
 */
data class UiNode(
    val index: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: UiRect,
    /** Есть скрытые действия (см. [hiddenActions]) — в снапшоте это флаг `has_actions`. */
    val hasActions: Boolean = false,
) {
    val label: String?
        get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
}

data class UiRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** Результат снапшота: показанные узлы плюс честные сведения об обрезке для модели. */
data class UiSnapshotResult(
    val nodes: List<UiNode>,
    val total: Int,
    val screenHeight: Int,
    val fromOwnWindow: Boolean,
)

/**
 * Отбор позиций узлов для показа модели при переполнении: сначала actionable и подписанные,
 * с гарантией покрытия третей экрана — элементы снизу не выпадают из выборки.
 * Возвращает позиции из [nodes] в порядке показа; при [screenHeight] <= 0 покрытия по третям нет.
 */
fun selectPositions(nodes: List<UiNode>, limit: Int, screenHeight: Int = 0): List<Int> {
    if (limit <= 0) return emptyList()
    if (nodes.size <= limit) return nodes.indices.toList()
    fun score(node: UiNode): Int =
        (if (node.clickable || node.editable || node.scrollable || node.hasActions) 2 else 0) +
            (if (node.label != null) 1 else 0)
    if (screenHeight <= 0) {
        return nodes.indices.sortedByDescending { score(nodes[it]) }.take(limit)
    }
    val third = screenHeight / 3
    val buckets = nodes.indices.groupBy { i ->
        val y = nodes[i].bounds.centerY
        when {
            y < third -> 0
            y < third * 2 -> 1
            else -> 2
        }
    }
    val queues = listOf(0, 1, 2).map { bucket ->
        (buckets[bucket] ?: emptyList()).sortedByDescending { score(nodes[it]) }.toMutableList()
    }
    val picked = ArrayList<Int>(limit)
    while (picked.size < limit && queues.any { it.isNotEmpty() }) {
        for (queue in queues) {
            if (picked.size >= limit) break
            queue.removeFirstOrNull()?.let { picked.add(it) }
        }
    }
    return picked
}

/**
 * Компактный текст снимка для модели: только информативные или действуемые узлы.
 * Формат строки: `[3] Button «Отправить» (clickable) [100,200-300,250]`.
 * Флаг `has_actions` — у узла есть скрытые действия (см. [formatNodeActions]).
 */
fun formatSnapshot(
    nodes: List<UiNode>,
    total: Int = nodes.size,
    screenHeight: Int = 0,
    fromOwnWindow: Boolean = false,
): String {
    val header = buildList {
        if (screenHeight > 0) add("экран 0..$screenHeight по вертикали")
        if (fromOwnWindow) add("внимание: снимок из окна OAssist, элементы приложения под панелью могут быть скрыты")
        if (total > nodes.size) add("список обрезан: показано ${nodes.size} из $total")
    }
    val headerText = if (header.isEmpty()) "" else header.joinToString("; ") + "\n"
    if (nodes.isEmpty()) return headerText + "Экран пуст или недоступен"
    return headerText + nodes.joinToString("\n") { node ->
        val flags = buildList {
            if (node.clickable) add("clickable")
            if (node.editable) add("editable")
            if (node.scrollable) add("scrollable")
            if (node.hasActions) add("has_actions")
        }.joinToString(",")
        val shortClass = node.className.substringAfterLast('.')
        val label = node.label?.let { " «$it»" } ?: ""
        val flagsText = if (flags.isNotEmpty()) " ($flags)" else ""
        val b = node.bounds
        "[${node.index}] $shortClass$label$flagsText [${b.left},${b.top}-${b.right},${b.bottom}]"
    }
}

/**
 * Скрытое действие ноды для списка в `ui_get_actions`.
 * [index] — позиция после [hiddenActions]; [id] — сырой id `AccessibilityAction`;
 * [label] — подпись кастомного действия («Ответить»), null у стандартных.
 */
data class UiNodeAction(val index: Int, val id: Int, val label: String?)

/**
 * Действия, уже отражённые в снимке другими средствами: click — флагом clickable,
 * set_text — editable + ui_set_text, прокрутка — scrollable + ui_scroll.
 * Перечислять их отдельно незачем — это и есть экономия снапшота.
 */
private val COVERED_ACTION_IDS = setOf(
    AccessibilityNodeInfo.ACTION_CLICK,
    AccessibilityNodeInfo.ACTION_SET_TEXT,
    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
)

/** Внутренности TalkBack (фокус и чтение): TalkBack управляет ими сам, агенту это шум. */
private val INTERNAL_ACTION_IDS = setOf(
    AccessibilityNodeInfo.ACTION_FOCUS,
    AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
    AccessibilityNodeInfo.ACTION_SELECT,
    AccessibilityNodeInfo.ACTION_CLEAR_SELECTION,
    AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
    AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
    AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT,
    AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT,
)

/**
 * «Скрытое» действие — то, чего модель не видит в остальных полях снимка:
 * подпись кастомного действия (всегда — framework-стандартные действия label
 * не имеют вовсе, а переопределённый label и означает семантику вроде
 * «Ответить»), долгое нажатие и прочие операции сверх базы. Неизвестные id —
 * fail-open: лучше показать (в т.ч. rich-действия API 23+ вроде
 * show_on_screen/context_click), чем потерять доступное.
 */
fun isHiddenAction(id: Int, label: String?): Boolean = when {
    label != null -> true
    id in COVERED_ACTION_IDS -> false
    id in INTERNAL_ACTION_IDS -> false
    else -> true
}

/** Отбор скрытых действий с перенумерацией: индексы списка берёт `ui_perform_action`. */
fun hiddenActions(actions: List<UiNodeAction>): List<UiNodeAction> =
    actions.filter { isHiddenAction(it.id, it.label) }.mapIndexed { i, a -> a.copy(index = i) }

/** Канонические имена публичных действий — english-идентификаторы, как флаги снапшота. */
fun standardActionName(id: Int): String = when (id) {
    AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
    AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
    AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
    AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
    AccessibilityNodeInfo.ACTION_COPY -> "copy"
    AccessibilityNodeInfo.ACTION_PASTE -> "paste"
    AccessibilityNodeInfo.ACTION_CUT -> "cut"
    AccessibilityNodeInfo.ACTION_SET_SELECTION -> "set_selection"
    else -> "action_$id"
}

/** Имя действия для вывода модели: подпись кастомного действия, иначе каноническое имя. */
fun actionName(action: UiNodeAction): String =
    "«${action.label?.takeIf { it.isNotBlank() } ?: standardActionName(action.id)}»"

/**
 * Список действий элемента для модели: `[0] «Ответить»` / `[1] «long_click»`.
 * Пустой список — не ошибка: модель просто узнаёт, что скрытых действий нет.
 */
fun formatNodeActions(nodeIndex: Int, nodeLabel: String?, actions: List<UiNodeAction>): String {
    val title = nodeLabel?.takeIf { it.isNotBlank() }?.let { " «$it»" } ?: ""
    if (actions.isEmpty()) {
        return "У элемента №$nodeIndex$title скрытых действий нет — нажатие, ввод и прокрутка " +
            "выполняются через ui_click/ui_set_text/ui_scroll"
    }
    return "Действия элемента №$nodeIndex$title:\n" +
        actions.joinToString("\n") { "[${it.index}] ${actionName(it)}" }
}
