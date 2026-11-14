package ru.outsideproarts.oassist.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.ui.components.ChatScrollMemory
import ru.outsideproarts.oassist.core.ui.components.ChatScrollPosition
import ru.outsideproarts.oassist.core.ui.components.ChatScrollRequester
import ru.outsideproarts.oassist.core.ui.components.ChatScrollToEndButton
import ru.outsideproarts.oassist.core.ui.components.isChatEnd
import ru.outsideproarts.oassist.core.ui.components.rememberChatListScroll
import ru.outsideproarts.oassist.core.ui.R as UiR

/**
 * Поведение прокрутки ленты чата (rememberChatListScroll + ChatScrollToEndButton):
 * восстановление позиции, следование за концом только у нижнего края, кнопка
 * «Прокрутить в конец». Последний элемент ленты выше вьюпорта — проверяет, что
 * «в конец» доводит до низа ленты, а не до верха последнего элемента.
 */
@RunWith(AndroidJUnit4::class)
class ChatListScrollBehaviorTest {

    @get:Rule val compose = createComposeRule()

    private val usedKeys = mutableListOf<String>()

    @After
    fun cleanupMemory() {
        usedKeys.forEach { ChatScrollMemory.remove(it) }
    }

    @Test
    fun requestRestoresSavedPositionOnOpen() =
        withList(count = 30, request = { it.scrollTo(ChatScrollPosition(10, 0)) }) { state, _, _ ->
            compose.runOnIdle { assertEquals(10, state.firstVisibleItemIndex) }
        }

    @Test
    fun returningToScreenRestoresPositionFromMemory() {
        val chatKey = uniqueKey("chat")
        ChatScrollMemory.save(chatKey, ChatScrollPosition(12, 0))
        withList(count = 30, chatId = chatKey) { state, _, _ ->
            compose.runOnIdle { assertEquals(12, state.firstVisibleItemIndex) }
        }
    }

    @Test
    fun scrollToEndButtonScrollsToTheVeryEndAndHides() =
        withList(count = 30) { state, _, _ ->
            val label = scrollLabel()
            compose.onNodeWithContentDescription(label).assertExists()
            compose.onNodeWithContentDescription(label).performClick()
            compose.waitUntil(5_000) { state.isChatEnd() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
            compose.runOnIdle { assertTrue(state.isChatEnd()) }
        }

    @Test
    fun contentGrowthFollowsOnlyAtTheBottomEdge() =
        withList(count = 30) { state, requester, items ->
            // Свежая лента наверху: задание «в конец» (как отправка сообщения) —
            // прокрутка в самый конец и следование за ростом.
            compose.runOnIdle { requester.scrollToEnd() }
            compose.waitUntil(5_000) { state.isChatEnd() }
            compose.waitForIdle()
            compose.runOnIdle { items.add("item-after-end") }
            // Условие ждёт и измерение роста: до layout'а isChatEnd() тривиально
            // true по прошлой геометрии — иначе возможна ложная зелёная.
            compose.waitUntil(5_000) {
                state.layoutInfo.totalItemsCount == items.size && state.isChatEnd()
            }

            // Пользователь прокрутил вверх (жест к низу экрана — к началу ленты):
            // рост ленты не должен дёргать экран.
            compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(state.isChatEnd()) }
            val before = compose.runOnIdle {
                state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            }
            compose.runOnIdle { items.add("item-after-up") }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(before.first, state.firstVisibleItemIndex)
                assertEquals(before.second, state.firstVisibleItemScrollOffset)
                assertFalse(state.isChatEnd())
            }
        }

    private fun uniqueKey(prefix: String): String =
        "$prefix-${usedKeys.size}".also { usedKeys.add(it) }

    private fun scrollLabel(): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(UiR.string.chats_scroll_to_end)

    private fun withList(
        count: Int,
        chatId: String? = null,
        request: (ChatScrollRequester) -> Unit = {},
        action: (LazyListState, ChatScrollRequester, MutableList<String>) -> Unit,
    ) {
        val items = mutableStateListOf<String>().apply {
            addAll((0 until count).map { "item-$it" })
        }
        val draftKey = uniqueKey("draft")
        val requester = ChatScrollRequester().apply { request(this) }
        lateinit var listState: LazyListState
        compose.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                TestChatList(
                    listState = listState,
                    requester = requester,
                    items = items,
                    chatId = chatId,
                    draftKey = draftKey,
                )
            }
        }
        compose.waitForIdle()
        action(listState, requester, items)
    }
}

@Composable
private fun TestChatList(
    listState: LazyListState,
    requester: ChatScrollRequester,
    items: List<String>,
    chatId: String?,
    draftKey: String,
) {
    val scrollState = rememberChatListScroll(
        listState = listState,
        requester = requester,
        chatId = chatId,
        draftKey = draftKey,
        contentKey = listOf(items.size),
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items) { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Последний элемент выше вьюпорта: «в конец» — до его низа.
                        .height(if (label == items.last()) 1200.dp else 200.dp),
                )
            }
        }
        ChatScrollToEndButton(
            state = scrollState,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
