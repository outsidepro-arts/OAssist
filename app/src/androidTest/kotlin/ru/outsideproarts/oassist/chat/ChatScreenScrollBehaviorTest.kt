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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry
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
 *
 * Ходы лежат в БД (экран перечитывает открытый диалог при появлении), лента
 * построена той же проекцией, что и на экране: пара реплик на ход, сообщение
 * «msg-N» — под индексом 2N.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenScrollBehaviorTest {

    @get:Rule val compose = createComposeRule()

    private val chatId = "scroll-test-chat"
    private val fixture = ChatPersistenceFixture()
    private val previousRepository = checkNotNull(ChatHandle.get())
    private val entries = (0 until 40).map { n ->
        StoredTurnEntry("turn-$n", StoredTurn(userText = "msg-$n"))
    }
    private val vm = ChatSessionViewModel().apply {
        this.chatId = this@ChatScreenScrollBehaviorTest.chatId
        turnStore.addAll(entries)
        // Проекция loadTurnsIntoUi: перечитывание при появлении не меняет ленту.
        messages.addAll(entries.flatMap { entry ->
            listOf(
                ChatItem(ChatRole.USER, answer = entry.turn.userText, turnId = entry.id),
                ChatItem(
                    ChatRole.ASSISTANT,
                    work = entry.turn.work,
                    answer = entry.turn.answer,
                    usage = entry.turn.usage,
                    turnId = entry.id,
                ),
            )
        })
    }

    init {
        runBlocking {
            fixture.repository.createChat(AssistMode.CHAT, "Scroll", chatId)
            entries.forEach { fixture.repository.appendTurn(chatId, it.turn, it.id) }
        }
        ChatHandle.register(fixture.repository)
    }

    @After
    fun cleanup() {
        ChatScrollMemory.remove(chatId)
        vm.scope.cancel()
        ChatHandle.register(previousRepository)
        fixture.close()
    }

    @Test
    fun returningToScreenDoesNotRestartFromTheBeginning() {
        // «msg-20» — под индексом 40 (пара реплик на ход).
        ChatScrollMemory.save(chatId, ChatScrollPosition(40, 0))
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
        // Экран перечитывает открытый диалог при появлении — дождаться готовности.
        compose.waitUntil(10_000) { !vm.isLoadingHistory }
    }
}
