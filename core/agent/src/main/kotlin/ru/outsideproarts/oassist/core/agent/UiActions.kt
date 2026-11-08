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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.device.PrivExecutor
import java.util.ArrayDeque

/**
 * Операции над экраном через пассивный AccessibilityService.
 * Сервис поставляется приложением ([serviceProvider]); если он выключен —
 * явная ошибка с подсказкой, а не молчаливый пропуск.
 *
 * Фолбэк жестов (M5.1): если сервис есть, но жест отклонён —
 * повтор через привилегии ([privProvider]: `input tap/swipe/keyevent`).
 * Без привилегий фолбэк честно недоступен (Noop).
 */
class UiActions(
    private val serviceProvider: () -> AccessibilityService?,
    private val privProvider: suspend () -> PrivExecutor = { NoopExecutor() },
    private val overlayControl: () -> OverlayControl? = { null },
    private val accessGranted: () -> Boolean = { false },
    private val requestReconnect: suspend () -> Unit = {},
    private val connectTimeoutMs: Long = A11Y_CONNECT_TIMEOUT_MS,
    private val connectPollMs: Long = A11Y_CONNECT_POLL_MS,
) {

    private val observationMutex = Mutex()
    /** Захват кадра один за раз: параллельные потоки (тулы ассистента, фолбэк assist-сессии) сами себе не устраивают троттлинг. */
    private val captureMutex = Mutex()
    private val observations = UiObservationRegistry<AccessibilityNodeInfo> { it.recycle() }
    internal var dispatchSwipe: suspend (Int, Int, Int, Int, Long, (Boolean) -> Unit) -> Result<Boolean> =
        ::dispatchAccessibilitySwipe
    private val STALE_SNAPSHOT_MESSAGE = "Снимок или элемент устарел — вызови ui_get_screen заново"

    private suspend fun serviceOrError(): Result<AccessibilityService> {
        serviceProvider()?.let { return Result.success(it) }
        if (!accessGranted()) {
            return Result.failure(IllegalStateException(SERVICE_DISABLED_MESSAGE))
        }
        val connected = awaitReconnected(connectTimeoutMs, connectPollMs, requestReconnect, serviceProvider)
            ?: return Result.failure(IllegalStateException(SERVICE_NOT_CONNECTED_MESSAGE))
        return Result.success(connected)
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

    /**
     * Блокирующие вызовы accessibility-фреймворка (список окон, [AccessibilityWindowInfo.getRoot])
     * никогда не выполняются на главном потоке: ответ на них обязана выдать наш сервис доступности
     * на том же главном потоке — вызов с главного потока сам себя блокирует до таймаута (ANR).
     */
    private suspend fun <T> offMain(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    @Suppress("DEPRECATION")
    private suspend fun targetRoot(service: AccessibilityService): RootPick = offMain {
        val own = service.packageName
        val windows = service.windows
        val index = pickWindowIndex(
            windows.map {
                UiWindowState(it.root?.packageName?.toString(), it.type, it.isActive, it.isFocused)
            },
            own,
        )
        if (index != null) return@offMain RootPick(windows[index].root, false)
        val fallback = service.rootInActiveWindow
        RootPick(fallback, fallback?.packageName?.toString() == own)
    }

    suspend fun snapshot(): Result<UiSnapshotResult> = observationMutex.withLock {
        observations.clear()
        overlayHidden(settleMs = SNAPSHOT_SETTLE_MS) { snapshotDirect(publish = true) }
    }

    @Suppress("DEPRECATION")
    private suspend fun snapshotDirect(publish: Boolean = false): Result<UiSnapshotResult> {
        val service = serviceOrError().getOrElse { return Result.failure(it) }
        val pick = targetRoot(service)
        val root = pick.root ?: return Result.failure(IllegalStateException("Нет активного окна"))
        return try {
            val walk = collectCandidates(root)
            try {
                val screenHeight = runCatching { service.resources.displayMetrics.heightPixels }.getOrDefault(0)
                val positions = selectPositions(walk.nodes, SHOW_NODES, screenHeight)
                val shown = positions.mapIndexed { display, pos -> walk.nodes[pos].copy(index = display) }
                val snapshotId = if (publish) java.util.UUID.randomUUID().toString() else null
                if (snapshotId != null) {
                    val retained = positions.mapIndexed { display, pos ->
                        val ref = walk.refs[pos]
                        UiObservedNode(AccessibilityNodeInfo.obtain(ref), fingerprint(ref, shown[display]))
                    }
                    observations.publish(snapshotId, retained)
                }
                Result.success(UiSnapshotResult(shown, walk.total, screenHeight, pick.fromOwnWindow, snapshotId))
            } finally {
                walk.refs.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    suspend fun click(index: Int, snapshotId: String?): ToolResult = observationMutex.withLock {
        try {
            val direct = performOnFreshNode(index, snapshotId, "нажать") { node ->
                node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (direct.success) direct
            else gestureThrough { touchNodeCenter(index, snapshotId, "нажать", longClick = false) }
        } finally { observations.clear() }
    }

    suspend fun longClick(index: Int, snapshotId: String?): ToolResult = observationMutex.withLock {
        try {
            val direct = performOnFreshNode(index, snapshotId, "долго нажать") { node ->
                node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            if (direct.success) direct
            else gestureThrough { touchNodeCenter(index, snapshotId, "долго нажать", longClick = true) }
        } finally { observations.clear() }
    }

    suspend fun setText(index: Int, text: String, snapshotId: String?): ToolResult = observationMutex.withLock {
        try {
            performOnFreshNode(index, snapshotId, "ввести текст") { node ->
                if (!node.isEditable && node.className?.toString() != "android.widget.EditText") return@performOnFreshNode false
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        } finally { observations.clear() }
    }

    suspend fun scroll(index: Int?, direction: String, snapshotId: String?): ToolResult = observationMutex.withLock {
        val forward = when (direction.lowercase()) {
            "down", "forward", "вниз" -> true
            "up", "backward", "вверх" -> false
            else -> return@withLock ToolResult(false, "Направление: down/up")
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        try {
            if (index != null) {
                performOnFreshNode(index, snapshotId, "прокрутить") { it.isScrollable && it.performAction(action) }
            } else {
                val service = serviceOrError().getOrElse { return@withLock ToolResult(false, it.message ?: "Сервис недоступен") }
                val root = targetRoot(service).root ?: return@withLock ToolResult(false, "Нет активного окна")
                try {
                    val walk = collectCandidates(root)
                    try {
                        val target = walk.refs.firstOrNull { it.isScrollable }
                            ?: return@withLock ToolResult(false, "Прокручиваемых узлов нет")
                        if (offMain { target.performAction(action) }) ToolResult(true, "Удалось прокрутить")
                        else ToolResult(false, "Не удалось прокрутить")
                    } finally { walk.refs.forEach { it.recycle() } }
                } finally { root.recycle() }
            }
        } finally { observations.clear() }
    }

    suspend fun nodeActions(index: Int, snapshotId: String?): ToolResult = observationMutex.withLock {
        val hit = resolveCurrentNode(index, snapshotId).getOrElse {
            return@withLock ToolResult(false, it.message ?: STALE_SNAPSHOT_MESSAGE)
        }
        try {
            val actions = hiddenActions(actionListOf(hit.info))
            if (!observations.advertise(snapshotId, index, actions)) {
                observations.clear()
                return@withLock ToolResult(false, STALE_SNAPSHOT_MESSAGE)
            }
            val label = hit.node.label ?: hit.node.className.substringAfterLast('.')
            ToolResult(true, formatNodeActions(index, label, actions))
        } finally { hit.info.recycle() }
    }

    suspend fun performNodeAction(index: Int, actionIndex: Int, snapshotId: String?): ToolResult = observationMutex.withLock {
        try {
            val hit = resolveCurrentNode(index, snapshotId).getOrElse {
                return@withLock ToolResult(false, it.message ?: STALE_SNAPSHOT_MESSAGE)
            }
            try {
                val actions = hiddenActions(actionListOf(hit.info))
                val action = observations.action(snapshotId, index, actionIndex, actions)
                    ?: return@withLock ToolResult(false, "Список действий изменился или не был запрошен — вызови ui_get_screen и ui_get_actions заново")
                val name = actionName(action)
                if (offMain { hit.info.performAction(action.id) }) {
                    ToolResult(true, "Выполнено действие $name (элемент №$index)")
                } else {
                    ToolResult(false, "Не удалось выполнить действие $name (элемент №$index)")
                }
            } finally { hit.info.recycle() }
        } finally { observations.clear() }
    }

    suspend fun globalAction(action: String): ToolResult = observationMutex.withLock {
        try {
            val service = serviceOrError().getOrElse { return@withLock ToolResult(false, it.message ?: "Сервис недоступен") }
            val global = when (action.lowercase()) {
                "back", "назад" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home", "домой" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents", "недавние" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> return@withLock ToolResult(false, "Действие: back/home/recents")
            }
            if (offMain { service.performGlobalAction(global) }) return@withLock ToolResult(true, "Выполнено: $action")
            val keyCode = when (action.lowercase()) {
                "back", "назад" -> 4
                "home", "домой" -> 3
                else -> 187
            }
            privProvider().inputKeyevent(keyCode).fold(
                onSuccess = { ToolResult(true, "Выполнено: $action (фолбэк input)") },
                onFailure = { ToolResult(false, "Не удалось выполнить $action: ${it.message}") },
            )
        } finally { observations.clear() }
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): ToolResult = observationMutex.withLock {
        try { gestureThrough { swipeInternal(fromX, fromY, toX, toY, durationMs) } }
        finally { observations.clear() }
    }

    private suspend fun swipeInternal(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): ToolResult {
        val duration = durationMs.coerceIn(1, 5000)
        val done = CompletableDeferred<Boolean>()
        val dispatched = dispatchSwipe(fromX, fromY, toX, toY, duration) { done.complete(it) }
            .getOrElse { return ToolResult(false, it.message ?: "Сервис недоступен") }
        if (!dispatched) {
            return swipeFallback(fromX, fromY, toX, toY, durationMs, "Жест отклонён системой")
        }
        return when (withTimeoutOrNull(duration + GESTURE_TIMEOUT_MS) { done.await() }) {
            true -> ToolResult(true, "Свайп выполнен")
            false -> ToolResult(false, "Свайп отменён системой; он мог выполниться частично")
            null -> ToolResult(false, "Результат свайпа неизвестен: время ожидания истекло; жест мог выполниться")
        }
    }

    private suspend fun dispatchAccessibilitySwipe(
        fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long, callback: (Boolean) -> Unit,
    ): Result<Boolean> = offMain {
        val service = serviceOrError().getOrElse { return@offMain Result.failure(it) }
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        Result.success(service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(false)
                }
            },
            Handler(Looper.getMainLooper()),
        ))
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

    private suspend fun nodeCenter(index: Int, snapshotId: String?): Pair<Int, Int>? {
        val hit = resolveCurrentNode(index, snapshotId).getOrNull() ?: return null
        return try {
            val b = hit.node.bounds
            ((b.left + b.right) / 2) to ((b.top + b.bottom) / 2)
        } finally { hit.info.recycle() }
    }

    private suspend fun touchNodeCenter(index: Int, snapshotId: String?, verb: String, longClick: Boolean): ToolResult {
        val center = nodeCenter(index, snapshotId) ?: return ToolResult(false, STALE_SNAPSHOT_MESSAGE)
        val priv = privProvider()
        val result = if (longClick) {
            priv.inputSwipe(center.first, center.second, center.first, center.second, 800)
        } else {
            priv.inputTap(center.first, center.second)
        }
        return result.fold(
            onSuccess = { ToolResult(true, "Удалось $verb (узел №$index, фолбэк input)") },
            onFailure = { ToolResult(false, "Не удалось $verb (узел №$index): ${it.message}") },
        )
    }

    suspend fun waitForText(text: String, timeoutMs: Long): ToolResult =
        observationMutex.withLock { overlayHidden { waitForTextLoop(text, timeoutMs) } }

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

    private suspend fun performOnNode(
        index: Int,
        snapshotId: String?,
        verb: String,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): ToolResult = performOnFreshNode(index, snapshotId, verb, action)

    private suspend fun performOnFreshNode(
        index: Int,
        snapshotId: String?,
        verb: String,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): ToolResult {
        val hit = resolveCurrentNode(index, snapshotId).getOrElse {
            return ToolResult(false, it.message ?: STALE_SNAPSHOT_MESSAGE)
        }
        return try {
            // action-лямбда выполняет performAction — блокирующий IPC с accessibility-сервисом.
            if (offMain { action(hit.info) }) ToolResult(true, "Удалось $verb (узел №$index)")
            else ToolResult(false, "Не удалось $verb (узел №$index)")
        } finally { hit.info.recycle() }
    }

    private class Walk(val refs: List<AccessibilityNodeInfo>, val nodes: List<UiNode>, val total: Int)

    /** Сырые действия ноды для pure-фильтра [hiddenActions] (Android-слой только перевод). */
    private fun actionListOf(node: AccessibilityNodeInfo): List<UiNodeAction> =
        runCatching { node.actionList.orEmpty() }.getOrDefault(emptyList())
            .mapIndexed { i, action -> UiNodeAction(i, action.id, action.label?.toString()) }

    @Suppress("DEPRECATION")
    private suspend fun collectCandidates(root: AccessibilityNodeInfo): Walk = offMain {
        val refs = mutableListOf<AccessibilityNodeInfo>()
        val out = mutableListOf<UiNode>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var total = 0
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val hasHidden = actionListOf(node).any { isHiddenAction(it.id, it.label) }
            val actionable = node.isClickable || node.isEditable || node.isScrollable || hasHidden ||
                node.className?.toString() == "android.widget.EditText"
            val informative = !text.isNullOrBlank() || !desc.isNullOrBlank()
            if ((actionable || informative) && depth > 0) {
                total++
                if (out.size < MAX_CANDIDATES) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    refs.add(AccessibilityNodeInfo.obtain(node))
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
                            hasActions = hasHidden,
                        ),
                    )
                }
            }
            if (node !== root) node.recycle()
        }
        Walk(refs, out, total)
    }

    /** Узел снимка вместе с живой ссылкой — нужен и для действия, и для снимка элемента. */
    private class NodeHit(val node: UiNode, val info: AccessibilityNodeInfo)

    private fun fingerprint(info: AccessibilityNodeInfo, node: UiNode): UiNodeFingerprint =
        UiNodeFingerprint(
            node = node,
            windowId = info.windowId,
            packageName = info.packageName?.toString(),
            resourceId = info.viewIdResourceName,
            enabled = info.isEnabled,
            longClickable = info.isLongClickable,
            visible = info.isVisibleToUser,
        )

    @Suppress("DEPRECATION")
    private suspend fun resolveCurrentNode(index: Int, snapshotId: String?): Result<NodeHit> {
        val service = serviceOrError().getOrElse { return Result.failure(it) }
        val expected = observations.expected(snapshotId, index)
            ?: return Result.failure(IllegalStateException(STALE_SNAPSHOT_MESSAGE))
        val root = targetRoot(service).root
            ?: return Result.failure(IllegalStateException("Нет активного окна"))
        return try {
            val walk = collectCandidates(root)
            try {
                val position = walk.refs.indices.firstOrNull { pos ->
                    val node = walk.nodes[pos].copy(index = index)
                    observations.matches(snapshotId, index, walk.refs[pos], fingerprint(walk.refs[pos], node))
                }
                if (position == null) Result.failure(IllegalStateException(STALE_SNAPSHOT_MESSAGE))
                else Result.success(NodeHit(expected.fingerprint.node, AccessibilityNodeInfo.obtain(walk.refs[position])))
            } finally { walk.refs.forEach { it.recycle() } }
        } finally { root.recycle() }
    }


/**
     * Скриншот текущего окна (панель ассистента скрыта). Почти чёрный кадр не «лечится»:
     * шторку скринридера извне отключить нельзя (скринридеры не трогаем), поэтому кадр
     * отдаётся с честной пометкой ([blackFrameNote]).
     */
    suspend fun screenshot(): ToolResult = overlayHidden(settleMs = SNAPSHOT_SETTLE_MS) {
        val shot = captureShot(null).getOrElse { return@overlayHidden screenshotError(it) }
        val name = shot.title?.takeIf { it.isNotBlank() } ?: "экран"
        shot.toResult("Снимок окна «$name»")
    }

    /**
     * Снимок прямоугольной области экрана в экранных координатах (как у `ui_get_screen`).
     * Любой выход за пределы окна — честная ошибка без кадра: обрезанный кадр модель
     * приняла бы за запрошенную область и сделала бы выводы по чужому содержимому.
     */
    suspend fun screenshotRegion(left: Int, top: Int, right: Int, bottom: Int): ToolResult =
        overlayHidden(settleMs = SNAPSHOT_SETTLE_MS) {
            if (left >= right || top >= bottom) {
                return@overlayHidden ToolResult(false, "Некорректная область: нужно left < right и top < bottom")
            }
            val service = serviceOrError().getOrElse {
                return@overlayHidden ToolResult(false, it.message ?: "Сервис недоступен")
            }
            val window = targetWindow(service)
                ?: return@overlayHidden ToolResult(false, "Нет активного окна для снимка")
            val win = Rect().also { window.getBoundsInScreen(it) }
            if (win.width() <= 0 || win.height() <= 0) {
                return@overlayHidden ToolResult(false, "У окна нет размеров — снимок невозможен")
            }
            if (left < win.left || top < win.top || right > win.right || bottom > win.bottom) {
                return@overlayHidden ToolResult(
                    false,
                    "Область [$left,$top-$right,$bottom] выходит за пределы окна " +
                        "[${win.left},${win.top}-${win.right},${win.bottom}] — уточни координаты по ui_get_screen",
                )
            }
            val shot = captureShot(Rect(left, top, right, bottom)).getOrElse {
                return@overlayHidden screenshotError(it)
            }
            shot.toResult("Снимок области [$left,$top-$right,$bottom]")
        }

    suspend fun screenshotNode(index: Int, snapshotId: String? = null): ToolResult = observationMutex.withLock {
        overlayHidden(settleMs = SNAPSHOT_SETTLE_MS) {
            val hit = resolveCurrentNode(index, snapshotId).getOrElse {
                return@overlayHidden ToolResult(false, it.message ?: STALE_SNAPSHOT_MESSAGE)
            }
            try {
                val bounds = Rect()
                hit.info.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    return@overlayHidden ToolResult(false, "Узел №$index имеет нулевые размеры — снимок невозможен")
                }
                val shot = captureShot(bounds).getOrElse { return@overlayHidden screenshotError(it) }
                val name = hit.node.label ?: hit.node.className.substringAfterLast('.')
                shot.toResult("Снимок элемента №$index «$name»")
            } finally { hit.info.recycle() }
        }
    }

    private class Shot(
        val part: ImagePart,
        val title: String?,
        val width: Int,
        val height: Int,
        val blackNote: String?,
        val clipped: Boolean = false,
    ) {
        fun toResult(subject: String): ToolResult {
            val sb = StringBuilder("$subject (${width}×$height) — изображение приложено.")
            if (clipped) {
                sb.append("\nОбласть выходит за границы кадра — снимок обрезан по видимой части.")
            }
            blackNote?.let { sb.append('\n').append(it) }
            return ToolResult(true, sb.toString(), images = listOf(part))
        }
    }

    /** Результат быстрого снимка для assist-фолбэка (см. [quickShot]). */
    data class QuickShot(val image: ImagePart, val black: Boolean, val windowCapture: Boolean)

    /**
     * Снимок окна, при [bounds] (экранные координаты) обрезанный по этой области.
     * Ретраи покрывают троттлинг платформы (слишком частые запросы); почти чёрный кадр
     * отдаётся как есть с пометкой — шторка скринридера извне не отключается.
     */
    private suspend fun captureShot(bounds: Rect?): Result<Shot> = offMain {
        captureMutex.withLock {
            val frame = withScreenshotRetry { captureFrame() }
                .getOrElse { return@withLock Result.failure(it) }
            finishFrame(frame, bounds)
        }
    }

    /**
     * Быстрый снимок для assist-фолбэка: без ожидания переподключения сервиса и settle —
     * укладывается в дедлайн assist-сессии. Null — снимок недоступен (сервис не подключён,
     * захват не удался).
     */
    suspend fun quickShot(): QuickShot? = offMain {
        captureMutex.withLock {
            val service = serviceProvider() ?: return@withLock null
            overlayHidden {
                // Один повтор: сразу после тулового захвата (тот же captureMutex) первый запрос
                // почти гарантированно упирается в троттлинг платформы.
                val frame = withScreenshotRetry(maxRetries = 1) { captureFrame(service) }
                    .getOrNull() ?: return@overlayHidden null
                val part = finishFrame(frame, null).getOrNull()?.part ?: return@overlayHidden null
                QuickShot(part, frame.black, frame.source == ScreenshotSource.WINDOW)
            }
        }
    }

    /** Кадр окна до сжатия: границы окна нужны, чтобы перевести экранные координаты в координаты кадра. */
    private class Frame(
        val bitmap: Bitmap,
        val window: Rect?,
        val title: String?,
        val black: Boolean,
        val source: ScreenshotSource,
    )

    private suspend fun captureFrame(): Result<Frame> {
        val service = serviceOrError().getOrElse { return Result.failure(it) }
        return captureFrame(service)
    }

    private suspend fun captureFrame(service: AccessibilityService): Result<Frame> {
        val window = targetWindow(service)
        val bounds = Rect()
        window?.getBoundsInScreen(bounds)
        val winBounds = bounds.takeIf { window != null && it.width() > 0 && it.height() > 0 }
        val source = screenshotSource(Build.VERSION.SDK_INT, window != null)
        val raw = if (source == ScreenshotSource.WINDOW) {
            takeWindowShot(service, requireNotNull(window).id)
        } else {
            takeDisplayShot(service)
        }
        val bitmap = raw.getOrElse { return Result.failure(it) }
        val cropped = cropTo(bitmap, screenshotFrameCrop(source, winBounds?.toUiRect())?.toRect())
        return Result.success(Frame(cropped, winBounds, window?.title?.toString(), isMostlyBlack(cropped), source))
    }

    /** Сжатие кадра в JPEG; [bounds] (экранные координаты) переводится в координаты кадра и вырезается. */
    private fun finishFrame(frame: Frame, bounds: Rect?): Result<Shot> {
        val requested = screenshotRegionCrop(bounds?.toUiRect(), frame.window?.toUiRect())?.toRect()
        val cropped = cropTo(frame.bitmap, requested)
        val clipped = requested != null &&
            (cropped.width != requested.width() || cropped.height != requested.height())
        val part = runCatching { ImageCodec.fromBitmap(cropped) }
            .getOrElse { return Result.failure(IllegalStateException("не удалось сжать снимок")) }
        val blackNote = if (frame.black) blackFrameNote(frame.source == ScreenshotSource.WINDOW) else null
        return Result.success(
            Shot(part, frame.title, cropped.width, cropped.height, blackNote, clipped),
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun targetWindow(service: AccessibilityService): AccessibilityWindowInfo? = offMain {
        val own = service.packageName
        val windows = service.windows
        val index = pickWindowIndex(
            windows.map { UiWindowState(it.root?.packageName?.toString(), it.type, it.isActive, it.isFocused) },
            own,
        )
        index?.let { windows[it] }
    }

    private suspend fun takeDisplayShot(service: AccessibilityService): Result<Bitmap> = offMain {
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
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(IllegalStateException("снимок не сделан за ${SCREENSHOT_TIMEOUT_MS}мс"))
    }

    @android.annotation.TargetApi(34)
    private suspend fun takeWindowShot(service: AccessibilityService, windowId: Int): Result<Bitmap> = offMain {
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
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { deferred.await() }
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

    private fun screenshotError(error: Throwable): ToolResult {
        if (error is ScreenshotException) {
            val message = screenshotErrorMessage(error.code)
                ?: "Не удалось сделать снимок (код ошибки ${error.code})"
            return ToolResult(false, message)
        }
        return ToolResult(false, error.message ?: "Не удалось сделать снимок")
    }
    companion object {
        const val SERVICE_DISABLED_MESSAGE =
            "Сервис доступности OAssist выключен — включите его в системных настройках " +
                "(если включали ранее — очистка фоновых могла сбросить доступ, включите заново)"
        const val SERVICE_NOT_CONNECTED_MESSAGE =
            "Сервис доступности OAssist включён, но ещё не подключился — откройте приложение OAssist, " +
                "подключение восстановится; если не помогает — разрешите OAssist автозапуск и фоновую работу " +
                "в настройках системы"
        private const val SHOW_NODES = 250
        private const val MAX_CANDIDATES = 1000
        private const val MAX_DEPTH = 32
        private const val SNAPSHOT_SETTLE_MS = 150L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L
        private const val GESTURE_TIMEOUT_MS = 5000L
        private const val MAX_WAIT_MS = 10000L
        private const val POLL_MS = 500L
    }
}

private const val A11Y_CONNECT_TIMEOUT_MS = 2_500L
private const val A11Y_CONNECT_POLL_MS = 100L
