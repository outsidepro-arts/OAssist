package ru.outsideproarts.oassist.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.ui.components.ChatScrollMemory
import ru.outsideproarts.oassist.core.ui.components.ChatScrollPosition
import ru.outsideproarts.oassist.feature.chat.ChatItem
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.ChatSessionViewModel
import ru.outsideproarts.oassist.core.ui.R as UiR

/**
 * Поведение прокрутки в основном окне чата: возврат на экран не начинает ленту
 * с начала, кнопка «Прокрутить в конец» доступна с клавиатуры/TalkBack и доводит
 * до последнего сообщения.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenScrollBehaviorTest {

    @get:Rule val compose = createComposeRule()

    private val chatId = "scroll-test-chat"
    private val vm = ChatSessionViewModel().apply {
        this.chatId = this@ChatScreenScrollBehaviorTest.chatId
        messages.addAll((0 until 40).map { ChatItem(ChatRole.USER, answer = "msg-$it") })
    }

    @After
    fun cleanup() {
        ChatScrollMemory.remove(chatId)
        vm.scope.cancel()
    }

    @Test
    fun returningToScreenDoesNotRestartFromTheBeginning() {
        ChatScrollMemory.save(chatId, ChatScrollPosition(20, 0))
        openChat()
        compose.onNodeWithText("msg-20").assertIsDisplayed()
        compose.onNodeWithText("msg-0").assertDoesNotExist()
    }

    @Test
    fun scrollToEndButtonJumpsToTheLastMessageAndHides() {
        openChat()
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(UiR.string.chats_scroll_to_end)
        compose.onNodeWithContentDescription(label).assertExists()
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("msg-39").assertIsDisplayed()
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    private fun openChat() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    viewModel = vm,
                    soundEnabled = false,
                    sendMessage = { _, _, _, _, _, _ -> error("Unexpected send") },
                )
            }
        }
        compose.waitForIdle()
    }
}
