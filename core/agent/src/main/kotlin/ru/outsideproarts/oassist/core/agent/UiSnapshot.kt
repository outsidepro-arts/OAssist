package ru.outsideproarts.oassist.core.agent

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
) {
    val label: String?
        get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
}

data class UiRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Компактный текст снимка для модели: только информативные или действуемые узлы.
 * Формат строки: `[3] Button «Отправить» (clickable) [100,200-300,250]`.
 */
fun formatSnapshot(nodes: List<UiNode>): String {
    if (nodes.isEmpty()) return "Экран пуст или недоступен"
    return nodes.joinToString("\n") { node ->
        val flags = buildList {
            if (node.clickable) add("clickable")
            if (node.editable) add("editable")
            if (node.scrollable) add("scrollable")
        }.joinToString(",")
        val shortClass = node.className.substringAfterLast('.')
        val label = node.label?.let { " «$it»" } ?: ""
        val flagsText = if (flags.isNotEmpty()) " ($flags)" else ""
        val b = node.bounds
        "[${node.index}] $shortClass$label$flagsText [${b.left},${b.top}-${b.right},${b.bottom}]"
    }
}
