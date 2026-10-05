package ru.outsideproarts.oassist.core.agent

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
