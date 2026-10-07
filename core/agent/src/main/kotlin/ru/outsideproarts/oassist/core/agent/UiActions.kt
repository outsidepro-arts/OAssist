package ru.outsideproarts.oassist.core.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.device.PrivExecutor
import java.util.ArrayDeque

/**
 * Операции над экраном через пассивный AccessibilityService.
 * Сервис поставляется приложением ([serviceProvider]); если он выключен —
 * явная ошибка с подсказкой, а не молчаливый пропуск.
 *
 * Фолбэк жестов (M5.1): если сервис есть, но жест отклонён/не сработал —
 * повтор через привилегии ([privProvider]: `input tap/swipe/keyevent`).
 * Без привилегий фолбэк честно недоступен (Noop).
 */
class UiActions(
    private val serviceProvider: () -> AccessibilityService?,
    private val privProvider: suspend () -> PrivExecutor = { NoopExecutor() },
    private val overlayControl: () -> OverlayControl? = { null },
) {

    private fun serviceOrError(): Result<AccessibilityService> {
        val service = serviceProvider()
            ?: return Result.failure(IllegalStateException(SERVICE_DISABLED_MESSAGE))
        return Result.success(service)
    }

    /** Скрыть наш оверлей на время координатного жеста: панель перехватывает тапы по приложению. */
    private suspend fun <T> gestureThrough(block: suspend () -> T): T {
        val control = overlayControl()
        return if (control != null) control.withOverlayHidden(block) else block()
    }

    /**
     * Корень дерева интерфейса: верхнее (по слою) интерактивное окно, кроме игнорируемых —
     * наш оверлей (панель ассистента), клавиатура и декор a11y мешают видеть приложение
     * под собой. Fallback — активное окно.
     */
    @Suppress("DEPRECATION")
    private fun targetRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val own = service.packageName
        val windows = service.windows
        val index = pickWindowIndex(
            windows.map {
                UiWindowState(it.root?.packageName?.toString(), it.type, it.isActive, it.isFocused)
            },
            own,
        )
        return index?.let { windows[it].root } ?: service.rootInActiveWindow
    }

    /** Свежий снимок дерева активного окна. */
    @Suppress("DEPRECATION")
    fun snapshot(): Result<List<UiNode>> {
        val service = serviceOrError().getOrElse { return Result.failure(it) }
        val root = targetRoot(service)
            ?: return Result.failure(IllegalStateException("Нет активного окна"))
        return try {
            Result.success(collectNodes(root))
        } finally {
            root.recycle()
        }
    }

    suspend fun click(index: Int): ToolResult {
        val direct = performOnNode(index, "нажать") { node ->
            node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (direct.success) return direct
        return gestureThrough { tapNodeCenter(index, "нажать") }
    }

    suspend fun longClick(index: Int): ToolResult {
        val direct = performOnNode(index, "долго нажать") { node ->
            node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        if (direct.success) return direct
        val center = nodeCenter(index) ?: return ToolResult(false, "Узел №$index не найден")
        return gestureThrough {
            privProvider().inputSwipe(center.first, center.second, center.first, center.second, 800)
                .fold(
                onSuccess = { ToolResult(true, "Удалось долго нажать (узел №$index, фолбэк input)") },
                onFailure = { ToolResult(false, "Не удалось долго нажать (узел №$index): ${it.message}") },
                )
        }
    }

    fun setText(index: Int, text: String): ToolResult = performOnNode(index, "ввести текст") { node ->
        if (!node.isEditable && node.className?.toString() != "android.widget.EditText") return@performOnNode false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(index: Int?, direction: String): ToolResult {
        val forward = when (direction.lowercase()) {
            "down", "forward", "вниз" -> true
            "up", "backward", "вверх" -> false
            else -> return ToolResult(false, "Направление: down/up")
        }
        val nodes = snapshot().getOrElse { return ToolResult(false, it.message ?: "Нет снимка") }
        val target = if (index != null) {
            nodes.find { it.index == index }
                ?: return ToolResult(false, "Узел №$index не найден")
        } else {
            nodes.firstOrNull { it.scrollable }
                ?: return ToolResult(false, "Прокручиваемых узлов нет")
        }
        if (!target.scrollable) return ToolResult(false, "Узел №${target.index} не прокручивается")
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performOnFreshNode(target.index, "прокрутить") { it.performAction(action) }
    }

    suspend fun globalAction(action: String): ToolResult {
        val service = serviceOrError().getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        val global = when (action.lowercase()) {
            "back", "назад" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home", "домой" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "недавние" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return ToolResult(false, "Действие: back/home/recents")
        }
        if (service.performGlobalAction(global)) {
            return ToolResult(true, "Выполнено: $action")
        }
        val keyCode = when (action.lowercase()) {
            "back", "назад" -> 4
            "home", "домой" -> 3
            else -> 187 // APP_SWITCH
        }
        return privProvider().inputKeyevent(keyCode).fold(
            onSuccess = { ToolResult(true, "Выполнено: $action (фолбэк input)") },
            onFailure = { ToolResult(false, "Не удалось выполнить $action: ${it.message}") },
        )
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): ToolResult =
        gestureThrough { swipeInternal(fromX, fromY, toX, toY, durationMs) }

    private suspend fun swipeInternal(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): ToolResult {
        val service = serviceOrError().getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 5000)))
            .build()
        val done = CompletableDeferred<Boolean>()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.complete(false)
                }
            },
            Handler(Looper.getMainLooper()),
        )
        if (!dispatched) {
            return swipeFallback(fromX, fromY, toX, toY, durationMs, "Жест отклонён системой")
        }
        val ok = withTimeoutOrNull(GESTURE_TIMEOUT_MS) { done.await() } ?: false
        if (ok) return ToolResult(true, "Свайп выполнен")
        return swipeFallback(fromX, fromY, toX, toY, durationMs, "Свайп не завершён")
    }

    /** Фолбэк свайпа через привилегии (`input swipe`). */
    private suspend fun swipeFallback(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
        reason: String,
    ): ToolResult = privProvider().inputSwipe(fromX, fromY, toX, toY, durationMs).fold(
        onSuccess = { ToolResult(true, "Свайп выполнен (фолбэк input)") },
        onFailure = { ToolResult(false, "$reason: ${it.message}") },
    )

    /** Центр узла из свежего снимка — для фолбэка тапов. */
    private fun nodeCenter(index: Int): Pair<Int, Int>? {
        val node = snapshot().getOrNull()?.find { it.index == index } ?: return null
        val b = node.bounds
        return ((b.left + b.right) / 2) to ((b.top + b.bottom) / 2)
    }

    private suspend fun tapNodeCenter(index: Int, verb: String): ToolResult {
        val center = nodeCenter(index) ?: return ToolResult(false, "Узел №$index не найден")
        return privProvider().inputTap(center.first, center.second).fold(
            onSuccess = { ToolResult(true, "Удалось $verb (узел №$index, фолбэк input)") },
            onFailure = { ToolResult(false, "Не удалось $verb (узел №$index): ${it.message}") },
        )
    }

    /** Ждать появления текста на экране (опрос снимков, лимит [timeoutMs]). */
    suspend fun waitForText(text: String, timeoutMs: Long): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1, MAX_WAIT_MS)
        while (true) {
            val nodes = snapshot().getOrNull()
            if (nodes != null && nodes.any { it.label?.contains(text, ignoreCase = true) == true }) {
                return ToolResult(true, "Текст «$text» найден")
            }
            if (System.currentTimeMillis() >= deadline) {
                return ToolResult(false, "Текст «$text» не появился за ${timeoutMs}мс")
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    private fun performOnNode(
        index: Int,
        verb: String,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): ToolResult = performOnFreshNode(index, verb, action)

    @Suppress("DEPRECATION")
    private fun performOnFreshNode(
        index: Int,
        verb: String,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): ToolResult {
        val service = serviceOrError().getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        val root = targetRoot(service)
            ?: return ToolResult(false, "Нет активного окна")
        return try {
            val node = findByIndex(root, index)
                ?: return ToolResult(false, "Узел №$index не найден")
            try {
                if (action(node)) ToolResult(true, "Удалось $verb (узел №$index)")
                else ToolResult(false, "Не удалось $verb (узел №$index)")
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<UiNode> {
        val out = mutableListOf<UiNode>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var index = 0
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val actionable = node.isClickable || node.isEditable || node.isScrollable ||
                node.className?.toString() == "android.widget.EditText"
            val informative = !text.isNullOrBlank() || !desc.isNullOrBlank()
            if ((actionable || informative) && depth > 0) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                out.add(
                    UiNode(
                        index = index++,
                        className = node.className?.toString() ?: "View",
                        text = text,
                        contentDescription = desc,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        bounds = UiRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    ),
                )
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    // Дочерние ссылки живут до recycle корня; объём ограничен MAX_NODES.
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return out
    }

    private fun findByIndex(root: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        // Индексы соответствуют порядку collectNodes (BFS, те же фильтры).
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var current = 0
        while (queue.isNotEmpty() && current < MAX_NODES + index) {
            val (node, depth) = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val actionable = node.isClickable || node.isEditable || node.isScrollable ||
                node.className?.toString() == "android.widget.EditText"
            val informative = !text.isNullOrBlank() || !desc.isNullOrBlank()
            if ((actionable || informative) && depth > 0) {
                if (current == index) return node
                current++
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return null
    }

    companion object {
        const val SERVICE_DISABLED_MESSAGE =
            "Сервис доступности OAssist выключен — включите его в системных настройках"
        private const val MAX_NODES = 100
        private const val MAX_DEPTH = 12
        private const val GESTURE_TIMEOUT_MS = 5000L
        private const val MAX_WAIT_MS = 10000L
        private const val POLL_MS = 500L
    }
}
