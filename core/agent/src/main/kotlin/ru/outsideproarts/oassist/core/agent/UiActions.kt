package ru.outsideproarts.oassist.core.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.core.provider.ImagePart
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

    private val curtain = ScreenCurtain(privProvider)

    private fun serviceOrError(): Result<AccessibilityService> {
        val service = serviceProvider()
            ?: return Result.failure(IllegalStateException(SERVICE_DISABLED_MESSAGE))
        return Result.success(service)
    }

    /** Скрыть наш оверлей на время координатного жеста: панель перехватывает тапы по приложению. */
    /** Скрытие оверлея на время [block]: захват жестов и снапшотов без перекрытия панелью. */
    private suspend fun <T> gestureThrough(block: suspend () -> T): T = overlayHidden(block = block)

    /**
     * Панель ассистента скрыта на время [block]; [settleMs] — ожидание обновления
     * a11y-дерева после скрытия (иначе узлы под панелью перечитываются со старым состоянием).
     */
    private suspend fun <T> overlayHidden(settleMs: Long = 0L, block: suspend () -> T): T {
        val control = overlayControl()
        return if (control != null) {
            control.withOverlayHidden {
                if (settleMs > 0) kotlinx.coroutines.delay(settleMs)
                block()
            }
        } else {
            block()
        }
    }

    /**
     * Корень дерева интерфейса: верхнее (по слою) интерактивное окно, кроме игнорируемых —
     * наш оверлей (панель ассистента), клавиатура и декор a11y мешают видеть приложение
     * под собой. Fallback — активное окно.
     */
    private class RootPick(val root: AccessibilityNodeInfo?, val fromOwnWindow: Boolean)

    @Suppress("DEPRECATION")
    private fun targetRoot(service: AccessibilityService): RootPick {
        val own = service.packageName
        val windows = service.windows
        val index = pickWindowIndex(
            windows.map {
                UiWindowState(it.root?.packageName?.toString(), it.type, it.isActive, it.isFocused)
            },
            own,
        )
        if (index != null) return RootPick(windows[index].root, false)
        val fallback = service.rootInActiveWindow
        return RootPick(fallback, fallback?.packageName?.toString() == own)
    }

    /** Свежий снимок дерева активного окна. */
    @Suppress("DEPRECATION")
    suspend fun snapshot(): Result<UiSnapshotResult> = overlayHidden(settleMs = SNAPSHOT_SETTLE_MS) { snapshotDirect() }

    @Suppress("DEPRECATION")
    private fun snapshotDirect(): Result<UiSnapshotResult> {
        val service = serviceOrError().getOrElse { return Result.failure(it) }
        val pick = targetRoot(service)
        val root = pick.root
            ?: return Result.failure(IllegalStateException("Нет активного окна"))
        return try {
            val walk = collectCandidates(root)
            val screenHeight = runCatching { service.resources.displayMetrics.heightPixels }.getOrDefault(0)
            val shown = selectPositions(walk.nodes, SHOW_NODES, screenHeight)
                .mapIndexed { display, pos -> walk.nodes[pos].copy(index = display) }
            Result.success(UiSnapshotResult(shown, walk.total, screenHeight, pick.fromOwnWindow))
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

    suspend fun scroll(index: Int?, direction: String): ToolResult {
        val forward = when (direction.lowercase()) {
            "down", "forward", "вниз" -> true
            "up", "backward", "вверх" -> false
            else -> return ToolResult(false, "Направление: down/up")
        }
        val nodes = snapshotDirect().map { it.nodes }.getOrElse { return ToolResult(false, it.message ?: "Нет снимка") }
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
        val node = snapshotDirect().getOrNull()?.nodes?.find { it.index == index } ?: return null
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
    suspend fun waitForText(text: String, timeoutMs: Long): ToolResult = overlayHidden { waitForTextLoop(text, timeoutMs) }

    private suspend fun waitForTextLoop(text: String, timeoutMs: Long): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1, MAX_WAIT_MS)
        while (true) {
            val nodes = snapshotDirect().getOrNull()?.nodes
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
        val pick = targetRoot(service)
        val root = pick.root
            ?: return ToolResult(false, "Нет активного окна")
        val screenH = runCatching { service.resources.displayMetrics.heightPixels }.getOrDefault(0)
        return try {
            val node = findByIndex(root, index, screenH)
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

    private class Walk(val refs: List<AccessibilityNodeInfo>, val nodes: List<UiNode>, val total: Int)

    private fun collectCandidates(root: AccessibilityNodeInfo): Walk {
        val refs = mutableListOf<AccessibilityNodeInfo>()
        val out = mutableListOf<UiNode>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var total = 0
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val actionable = node.isClickable || node.isEditable || node.isScrollable ||
                node.className?.toString() == "android.widget.EditText"
            val informative = !text.isNullOrBlank() || !desc.isNullOrBlank()
            if ((actionable || informative) && depth > 0) {
                total++
                if (out.size < MAX_CANDIDATES) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    refs.add(node)
                    out.add(
                        UiNode(
                            index = out.size,
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
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    // Дочерние ссылки живут до recycle корня; объём ограничен MAX_CANDIDATES.
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return Walk(refs, out, total)
    }

    private fun findByIndex(root: AccessibilityNodeInfo, index: Int, screenHeight: Int): AccessibilityNodeInfo? {
        // Индексы соответствуют показанному снапшоту: тот же BFS + selectPositions.
        val walk = collectCandidates(root)
        val shown = selectPositions(walk.nodes, SHOW_NODES, screenHeight)
        val pos = shown.getOrNull(index) ?: return null
        return walk.refs[pos]
    }

    /**
     * Скриншот текущего окна (панель ассистента скрыта). При чёрном кадре — best-effort
     * снятие шторки TalkBack на момент снимка (нужны Shizuku/root) с восстановлением;
     * не вышло — честное предупреждение, не тихий пустой кадр.
     */
    suspend fun screenshot(): ToolResult = overlayHidden(settleMs = SNAPSHOT_SETTLE_MS) {
        val first = captureShot()
        val shot = first.getOrElse { return@overlayHidden screenshotError(it) }
        if (!shot.black) return@overlayHidden shot.toResult()
        val restore = curtain.suspendTalkbackForShot() ?: return@overlayHidden shot.toResult(blackNote = true)
        try {
            val second = captureShot().getOrNull()
            return@overlayHidden if (second != null && !second.black) {
                second.toResult(liftedCurtain = true)
            } else {
                shot.toResult(blackNote = true, liftedCurtain = true)
            }
        } finally {
            restore()
        }
    }

    private class Shot(
        val part: ImagePart,
        val title: String?,
        val width: Int,
        val height: Int,
        val black: Boolean,
    ) {
        fun toResult(blackNote: Boolean = false, liftedCurtain: Boolean = false): ToolResult {
            val name = title?.takeIf { it.isNotBlank() } ?: "экран"
            val sb = StringBuilder("Снимок окна «$name» (${width}×$height) — изображение приложено.")
            if (liftedCurtain) {
                sb.append("\nШторка скринридера была временно снята для снимка и восстановлена.")
            }
            if (blackNote) {
                sb.append("\nКадр почти чёрный: возможно, шторка скринридера или защищённое окно — снимок может быть пустым.")
            }
            return ToolResult(true, sb.toString(), images = listOf(part))
        }
    }

    private class ScreenshotException(val code: Int) : Exception("screenshot error $code")

    private suspend fun captureShot(): Result<Shot> {
        val service = serviceOrError().getOrElse { return Result.failure(it) }
        val window = targetWindow(service)
        val bounds = Rect()
        window?.getBoundsInScreen(bounds)
        val raw = if (Build.VERSION.SDK_INT >= 34 && window != null) {
            takeWindowShot(service, window.id)
        } else {
            takeDisplayShot(service)
        }
        val bitmap = raw.getOrElse { return Result.failure(it) }
        val cropped = cropTo(bitmap, bounds.takeIf { window != null && it.width() > 0 && it.height() > 0 })
        val black = probeBlack(cropped)
        val part = runCatching { ImageCodec.fromBitmap(cropped) }
            .getOrElse { return Result.failure(IllegalStateException("не удалось сжать снимок")) }
        return Result.success(Shot(part, window?.title?.toString(), cropped.width, cropped.height, black))
    }

    @Suppress("DEPRECATION")
    private fun targetWindow(service: AccessibilityService): AccessibilityWindowInfo? {
        val own = service.packageName
        val windows = service.windows
        val index = pickWindowIndex(
            windows.map { UiWindowState(it.root?.packageName?.toString(), it.type, it.isActive, it.isFocused) },
            own,
        )
        return index?.let { windows[it] }
    }

    private suspend fun takeDisplayShot(service: AccessibilityService): Result<Bitmap> {
        val deferred = CompletableDeferred<Result<Bitmap>>()
        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    deferred.complete(bitmapFrom(screenshot))
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(Result.failure(ScreenshotException(errorCode)))
                }
            },
        )
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(IllegalStateException("снимок не сделан за ${SCREENSHOT_TIMEOUT_MS}мс"))
    }

    @android.annotation.TargetApi(34)
    private suspend fun takeWindowShot(service: AccessibilityService, windowId: Int): Result<Bitmap> {
        val deferred = CompletableDeferred<Result<Bitmap>>()
        service.takeScreenshotOfWindow(
            windowId,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    deferred.complete(bitmapFrom(screenshot))
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(Result.failure(ScreenshotException(errorCode)))
                }
            },
        )
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(IllegalStateException("снимок не сделан за ${SCREENSHOT_TIMEOUT_MS}мс"))
    }

    private fun bitmapFrom(screenshot: AccessibilityService.ScreenshotResult): Result<Bitmap> {
        val hw = screenshot.hardwareBuffer
            ?: return Result.failure(IllegalStateException("пустой буфер снимка"))
        val wrapped = runCatching { Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace) }.getOrNull()
        hw.close()
        val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
            ?: return Result.failure(IllegalStateException("не удалось декодировать снимок"))
        return Result.success(bitmap)
    }

    private fun cropTo(bitmap: Bitmap, bounds: Rect?): Bitmap {
        if (bounds == null) return bitmap
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val width = bounds.width().coerceIn(1, bitmap.width - left)
        val height = bounds.height().coerceIn(1, bitmap.height - top)
        if (left == 0 && top == 0 && width == bitmap.width && height == bitmap.height) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    private fun probeBlack(bitmap: Bitmap): Boolean {
        val pixels = runCatching {
            val probe = Bitmap.createScaledBitmap(bitmap, PROBE_SIZE, PROBE_SIZE, true)
            val out = IntArray(PROBE_SIZE * PROBE_SIZE)
            probe.getPixels(out, 0, PROBE_SIZE, 0, 0, PROBE_SIZE, PROBE_SIZE)
            if (probe !== bitmap) probe.recycle()
            out
        }.getOrNull() ?: return false
        return isMostlyBlack(pixels)
    }

    private fun screenshotError(error: Throwable): ToolResult {
        if (error is ScreenshotException) {
            return if (error.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                ToolResult(false, "Окно защищено от скриншотов (FLAG_SECURE) — снимок невозможен")
            } else {
                ToolResult(false, "Не удалось сделать снимок (код ошибки ${error.code})")
            }
        }
        return ToolResult(false, error.message ?: "Не удалось сделать снимок")
    }
    companion object {
        const val SERVICE_DISABLED_MESSAGE =
            "Сервис доступности OAssist выключен — включите его в системных настройках"
        private const val SHOW_NODES = 250
        private const val MAX_CANDIDATES = 1000
        private const val MAX_DEPTH = 32
        private const val SNAPSHOT_SETTLE_MS = 150L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L
        private const val PROBE_SIZE = 64
        private const val GESTURE_TIMEOUT_MS = 5000L
        private const val MAX_WAIT_MS = 10000L
        private const val POLL_MS = 500L
    }
}
