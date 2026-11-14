package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.ui.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Позиция прокрутки ленты сообщений. */
data class ChatScrollPosition(val index: Int, val offset: Int)

/**
 * Задание прокрутки ленты: [position] != null — восстановить позицию,
 * null — прокрутить в самый конец. Признак исполнения живёт в запросе, а не в
 * композиции: [ChatScrollRequester] живёт в ViewModel, а состояние `remember`
 * гибнет при уходе со экрана — старое задание не должно исполняться повторно
 * и затенять восстановление позиции при возврате.
 */
class ChatScrollRequest(val position: ChatScrollPosition?) {
    /** Исполнено UI; повторное применение того же объекта исключено. */
    internal var applied = false
}

/**
 * Источник заданий прокрутки. Ставится владельцем контента (ViewModel) в том же
 * синхронном блоке, что и изменение ленты, и исполняется UI один раз на задание
 * (сравнение по идентичности объекта — см. [ChatScrollRequest.applied]).
 */
class ChatScrollRequester {
    var request by mutableStateOf<ChatScrollRequest?>(null)
        private set

    /** Прокрутить к [position]; null — в самый конец ленты. */
    fun scrollTo(position: ChatScrollPosition?) {
        request = ChatScrollRequest(position)
    }

    /** Прокрутить в самый конец ленты. */
    fun scrollToEnd() = scrollTo(null)
}

/**
 * In-memory память позиций прокрутки по ключам диалогов. Живёт до перезапуска
 * процесса: позиция переживает переключение диалогов и экранов, но не
 * перезапуск приложения — осознанное ограничение решения «хранить в памяти».
 */
object ChatScrollMemory {
    private val positions = mutableMapOf<String, ChatScrollPosition>()

    @Synchronized
    fun save(key: String, position: ChatScrollPosition) {
        positions[key] = position
    }

    @Synchronized
    fun restore(key: String): ChatScrollPosition? = positions[key]

    @Synchronized
    fun remove(key: String) {
        positions.remove(key)
    }
}

/** Состояние прокрутки ленты чата: [atEnd] — лента у нижнего края. */
class ChatListScrollState(
    val listState: LazyListState,
    val atEnd: State<Boolean>,
)

/** Порог «у нижнего края»: ниже конца ленты осталось меньше этого расстояния. */
private val ChatEndThreshold = 8.dp

/** Лента у нижнего края: ниже нет элементов и последний видимый доведён до низа. */
fun LazyListState.isChatEnd(thresholdPx: Float = 0f): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index < info.totalItemsCount - 1) return false
    return (lastVisible.offset + lastVisible.size) - info.viewportEndOffset <= thresholdPx
}

/** Прокрутить к [position] с клампом к фактическому содержимому ленты. */
suspend fun LazyListState.scrollChatTo(position: ChatScrollPosition) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(position.index.coerceIn(0, lastIndex), position.offset.coerceAtLeast(0))
}

/**
 * Прокрутить в самый конец ленты. [animateScrollToItem] останавливается у верха
 * последнего элемента, выше вьюпорта, поэтому после доведения элемента до вида
 * низ элемента выравнивается по низу вьюпорта.
 */
suspend fun LazyListState.scrollChatToEnd(animate: Boolean = false) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) {
        if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    }
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val delta = (last.offset + last.size) - info.viewportEndOffset
    if (delta > 0) {
        // Цель задаётся сдвигом первого видимого элемента, поэтому считается от
        // размера последнего элемента: текущий сдвиг элемента уже входит в этот
        // сдвиг и при суммировании с delta недокручивает ленту до низа.
        val target = (last.size - info.viewportEndOffset).coerceAtLeast(0)
        if (animate) animateScrollToItem(lastIndex, target) else scrollToItem(lastIndex, target)
    }
}

/**
 * Поведение прокрутки ленты чата:
 * - позиция сохраняется в [ChatScrollMemory] по ключу диалога
 *   (`chatId ?: draftKey`) и восстанавливается при повторном открытии диалога
 *   и при возврате на экран со свежим состоянием ленты;
 * - задания [ChatScrollRequester] (восстановить позицию / в конец) исполняются
 *   однократно и подавляют следование за концом;
 * - при изменениях контента (новые сообщения, рост ответа, окончание генерации)
 *   лента следует за концом, только если пользователь у нижнего края; отправка
 *   своего сообщения ставит задание «в конец».
 *
 * [contentKey] — подпись контента ленты; каждое её изменение запускает политику.
 */
@Composable
fun rememberChatListScroll(
    listState: LazyListState,
    requester: ChatScrollRequester,
    chatId: String?,
    draftKey: String,
    contentKey: List<Any?>,
): ChatListScrollState {
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        ChatEndThreshold.toPx()
    }
    val atEnd = remember { mutableStateOf(true) }
    val memoryKey = chatId ?: draftKey

    // Позиция пишется по текущему ключу при каждом сдвиге ленты. Начальный
    // снимок при смене ключа отбрасывается: до первого движения это позиция
    // предыдущей ленты, она не должна попадать под новый ключ.
    LaunchedEffect(listState, memoryKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { (index, offset) ->
                ChatScrollMemory.save(memoryKey, ChatScrollPosition(index, offset))
            }
    }

    // Черновик стал сохранённым диалогом: позиция переезжает под его id.
    val previousKey = remember { AtomicReference(memoryKey) }
    LaunchedEffect(memoryKey) {
        val previous = previousKey.getAndSet(memoryKey)
        if (previous == draftKey && memoryKey != draftKey) {
            ChatScrollMemory.restore(draftKey)?.let { ChatScrollMemory.save(memoryKey, it) }
        }
    }

    // «У нижнего края» переоценивается только при устоявшихся прокрутках:
    // рост контента сам по себе не гасит следование за концом.
    LaunchedEffect(listState, thresholdPx) {
        // Первая оценка — после измерения кадра: до него layoutInfo пуст
        // и «у нижнего края» дало бы ложно true.
        withFrameNanos { }
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress -> if (!inProgress) atEnd.value = listState.isChatEnd(thresholdPx) }
    }

    val firstRun = remember { AtomicBoolean(true) }
    LaunchedEffect(requester.request, contentKey) {
        val request = requester.request
        val pendingRequest = if (request != null && !request.applied) request else null
        // Решения принимаются по layoutInfo после измерения кадра, в котором
        // применён контент: до него layoutInfo отражает прошлую ленту (счётчик
        // элементов и геометрия устарели, а scrollToItem по устаревшему клампу
        // уводит ленту не туда). Ожидание кадра корректно при любом порядке
        // «эффект до/после layout».
        withFrameNanos { }
        // Первый запуск потребляется после ожидания кадра: отменённый ожиданием
        // запуск не должен считаться «возвратом на экран».
        val isFirstRun = firstRun.getAndSet(false)
        when {
            pendingRequest != null -> {
                val position = pendingRequest.position
                if (position != null) listState.scrollChatTo(position) else listState.scrollChatToEnd()
                // Отметка — после скролла: отменённый середине скролл не должен
                // считаться исполненным, задание повторится при следующем запуске.
                pendingRequest.applied = true
                atEnd.value = listState.isChatEnd(thresholdPx)
            }
            isFirstRun -> {
                // Возврат на экран: свежее состояние ленты начинается с начала —
                // вернуть позицию из памяти, если она есть.
                val saved = ChatScrollMemory.restore(memoryKey)
                if (saved != null && listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    listState.scrollChatTo(saved)
                    atEnd.value = listState.isChatEnd(thresholdPx)
                }
            }
            atEnd.value && !listState.isScrollInProgress -> {
                listState.scrollChatToEnd()
                atEnd.value = listState.isChatEnd(thresholdPx)
            }
        }
    }
    return remember(listState) { ChatListScrollState(listState, atEnd) }
}

/**
 * Кнопка «Прокрутить в конец» поверх ленты: видна, только когда пользователь
 * прокрутил вверх от конца. Имя для TalkBack — [R.string.chats_scroll_to_end].
 */
@Composable
fun BoxScope.ChatScrollToEndButton(
    state: ChatListScrollState,
    modifier: Modifier = Modifier,
) {
    if (state.atEnd.value) return
    val label = stringResource(R.string.chats_scroll_to_end)
    val scope = rememberCoroutineScope()
    SmallFloatingActionButton(
        onClick = { scope.launch { state.listState.scrollChatToEnd(animate = true) } },
        modifier = modifier.oassistTouchTarget().oassistRole(Role.Button),
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardDoubleArrowDown,
            contentDescription = label,
        )
    }
}
