package ru.outsideproarts.oassist.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.ChatSessionViewModel
import ru.outsideproarts.oassist.feature.chat.R as ChatR
import ru.outsideproarts.oassist.core.ui.R as UiR

/**
 * Удаление сообщения и откат к сообщению в ленте чата: лента, БД и история
 * для модели меняются согласованно, удалённое не воскресает при дозаписи.
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageDeletionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var activeVm: ChatSessionViewModel

    @Test fun deletingMessageRemovesTurnFromScreenAndDatabase() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            fixture.repository.appendTurn(id, StoredTurn(userText = "second", answer = "answer second"))
            withScreen(fixture, null, "Draft") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 2 }
                // Пузыри ленты: [вы 1, ответ 1, вы 2, ответ 2] — удаляем второй ход.
                awaitActionMenus(4)
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[2]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_delete)).performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_delete_confirm)).performClick()
                compose.waitUntil(10_000) {
                    vm.turnStore.size == 1 && runBlocking { fixture.repository.loadTurns(id) }.size == 1
                }
                compose.runOnIdle {
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    assertEquals(listOf("first"), runBlocking { fixture.repository.loadTurns(id) }.map { it.userText })
                }
                // Следующий запрос не видит удалённый ход.
                send("third")
                compose.waitUntil(10_000) { requests.size == 1 }
                assertEquals(listOf("first", "answer first"), requests.single().map { it.content })
            }
        }
    }

    @Test fun rollbackKeepsTargetTurnAndTruncatesModelHistory() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            fixture.repository.appendTurn(id, StoredTurn(userText = "second", answer = "answer second"))
            withScreen(fixture, null, "Draft") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 2 }
                awaitActionMenus(4)
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_rollback)).performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_rollback_confirm)).performClick()
                compose.waitUntil(10_000) {
                    vm.turnStore.size == 1 && runBlocking { fixture.repository.loadTurns(id) }.size == 1
                }
                // Следующий запрос уходит с усечённой историей: второй ход не всплывает.
                send("third")
                compose.waitUntil(10_000) { requests.size == 1 }
                assertEquals(listOf("first", "answer first"), requests.single().map { it.content })
                // На последнем ходе откат скрыт: после него стирать нечего.
                // Пузыри ленты: [вы 1, ответ 1, вы 3, ответ 3].
                awaitActionMenus(4)
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[3]
                    .performClick()
                compose.onAllNodesWithText(context.getString(UiR.string.msg_action_rollback)).assertCountEquals(0)
            }
        }
    }

    @Test fun deletingMessageWaitsForItsPendingSaveAndTrimsScreenAfterDatabase() = runBlocking {
        GatedExecutor().use { gate ->
            ChatPersistenceFixture(transactionExecutor = gate).use { fixture ->
                val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
                withScreen(fixture, id, "Source", gate::releaseAll) { vm, _ ->
                    val pending = gate.pauseNext()
                    send("first")
                    assertTrue(pending.entered.await(10, TimeUnit.SECONDS))
                    // Ход ещё записывается: стирание встаёт в очередь после записи,
                    // лента усекается только после успешного стирания БД.
                    awaitActionMenus(2)
                    compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                        .performClick()
                    compose.onNodeWithText(context.getString(UiR.string.msg_action_delete)).performClick()
                    compose.onNodeWithText(context.getString(UiR.string.msg_delete_confirm)).performClick()
                    compose.runOnIdle {
                        assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    }
                    pending.release.countDown()
                    compose.waitUntil(10_000) {
                        vm.turnStore.isEmpty() && runBlocking { fixture.repository.loadTurns(id) }.isEmpty()
                    }
                }
            }
        }
    }

    @Test fun failedDeletionKeepsScreenAndDatabaseConsistentAndAllowsRetry() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            withScreen(fixture, null, "Draft") { vm, _ ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                fixture.database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_delete BEFORE DELETE ON turns BEGIN SELECT RAISE(ABORT, 'test abort'); END",
                )
                awaitActionMenus(2)
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_delete)).performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_delete_confirm)).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Сбой записи: вид и БД остаются согласованными, сообщение на месте.
                compose.runOnIdle {
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    assertEquals(listOf("first"), runBlocking { fixture.repository.loadTurns(id) }.map { it.userText })
                }
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                compose.waitUntil(10_000) {
                    vm.turnStore.isEmpty() && runBlocking { fixture.repository.loadTurns(id) }.isEmpty()
                }
            }
        }
    }

    /**
     * Дождаться отрисовки пузырей ленты: меню индексируются по дереву, а не по состоянию VM.
     * При таймауте падение описывает фактическое состояние ленты — без него флаки
     * на медленных устройствах не диагностируется.
     */
    private fun awaitActionMenus(count: Int) {
        var seen = 0
        try {
            compose.waitUntil(10_000) {
                seen = compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))
                    .fetchSemanticsNodes().size
                seen == count
            }
        } catch (timeout: Throwable) {
            val state = "menus=$seen/$count isStreaming=${activeVm.isStreaming} isLoadingHistory=${activeVm.isLoadingHistory} " +
                "turns=${activeVm.turnStore.map { it.turn.userText }} " +
                "rows=${activeVm.messages.map { "${it.role}:${it.turnId?.take(4)}:${it.answer.take(12)}" }}"
            throw AssertionError("Action menus never reached $count ($state)", timeout)
        }
    }

    private fun open(title: String) {
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_open_list)).performClick()
        compose.onNode(
            hasText(title) and SemanticsMatcher("chat summary with its date") {
                (it.config.getOrNull(SemanticsProperties.Text)?.size ?: 0) > 1
            },
        ).performClick()
    }

    private fun send(text: String) {
        // Текст задаётся семантическим действием, без IME: открытая клавиатура
        // сжимает вьюпорт ленты, и на узком экране (CI-эмулятор 320x640) нижние
        // пузыри выезжают из составленного дерева — действия над ними недоступны.
        compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint))
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { !activeVm.isStreaming }
    }

    private fun withScreen(
        fixture: ChatPersistenceFixture,
        id: String?,
        title: String,
        releasePending: () -> Unit = {},
        action: (ChatSessionViewModel, MutableList<List<ChatMessage>>) -> Unit,
    ) {
        val previousRepository = checkNotNull(ChatHandle.get())
        val previousNetwork = checkNotNull(NetworkHandle.get())
        val requests = mutableListOf<List<ChatMessage>>()
        val vm = ChatSessionViewModel().apply { chatId = id; chatTitle = title; titleLocked = true }
        activeVm = vm
        val visible = mutableStateOf(true)
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean) = flow {
                emit(ChatChunk.Content("answer"))
                emit(ChatChunk.Finish("stop"))
            }
        }
        ChatHandle.register(fixture.repository)
        NetworkHandle.register(object : NetworkHandle { override val isOnline = MutableStateFlow(true) })
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible.value) {
                        ChatScreen(
                            viewModel = vm,
                            soundEnabled = false,
                            sendMessage = { _, text, history, _, callback, pinCallback ->
                                requests.add(history)
                                val agent = AgentLoop(client, diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics)
                                AgentSession(agent.stream("fixture", null, history, text, onTurnMessages = callback, onPinChange = pinCallback), agent)
                            },
                        )
                    }
                }
            }
            compose.waitForIdle()
            action(vm, requests)
        } finally {
            releasePending()
            compose.runOnIdle { visible.value = false; vm.scope.cancel() }
            compose.waitForIdle()
            ChatHandle.register(previousRepository)
            NetworkHandle.register(previousNetwork)
        }
    }

    private class GatedExecutor : Executor, AutoCloseable {
        class Gate {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
        }
        private val executor = Executors.newSingleThreadExecutor()
        private val next = AtomicReference<Gate?>()
        private val gates = mutableListOf<Gate>()
        fun pauseNext(): Gate = Gate().also { check(next.compareAndSet(null, it)); gates.add(it) }
        override fun execute(command: Runnable) {
            val gate = next.getAndSet(null)
            executor.execute {
                if (gate != null) {
                    gate.entered.countDown()
                    check(gate.release.await(15, TimeUnit.SECONDS))
                }
                command.run()
            }
        }
        override fun close() {
            releaseAll()
            executor.shutdown()
            check(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
        fun releaseAll() { gates.forEach { it.release.countDown() } }
    }
}
