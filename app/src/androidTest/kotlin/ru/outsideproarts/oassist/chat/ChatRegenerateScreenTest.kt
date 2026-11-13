package ru.outsideproarts.oassist.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * Повтор генерации по сообщению: «Повторить» в действиях сообщения запускает
 * генерацию заново с вводом выбранного сообщения, удаляя его и все последующие.
 * Доступно на любом сообщении, включая реплику без записанного хода (ошибка
 * генерации), и без подтверждения, когда сообщение последнее.
 */
@RunWith(AndroidJUnit4::class)
class ChatRegenerateScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var activeVm: ChatSessionViewModel
    private var reply: () -> kotlinx.coroutines.flow.Flow<ChatChunk> = {
        flowOf(ChatChunk.Content("answer"), ChatChunk.Finish("stop"))
    }

    @Test fun regenerateLastMessageRestartsGenerationWithoutConfirmation() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            withScreen(fixture, id, "Source") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                // Пузыри ленты: [вы, ответ] — «Повторить» есть и на ответе ассистента.
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[1]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                // Последнее сообщение: подтверждение не требуется, повтор запущен.
                compose.onAllNodesWithText(context.getString(UiR.string.msg_regenerate_title)).assertCountEquals(0)
                compose.waitUntil(10_000) { requests.size == 1 && !activeVm.isStreaming }
                compose.runOnIdle {
                    // История — до выбранного хода (пустая), его ввод ушёл заново.
                    assertEquals("first" to emptyList<String>(), requests.single())
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    assertEquals("answer", vm.turnStore.single().turn.answer)
                    assertEquals(
                        listOf("first"),
                        runBlocking { fixture.repository.loadTurns(id) }.map { it.userText },
                    )
                }
            }
        }
    }

    @Test fun regenerateMiddleMessageWarnsAndRemovesFollowingMessages() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            fixture.repository.appendTurn(id, StoredTurn(userText = "second", answer = "answer second"))
            withScreen(fixture, id, "Source") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 2 }
                // Пузыри: [вы 1, ответ 1, вы 2, ответ 2] — повтор первого хода.
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                // Есть последующие сообщения — предупреждение об их потере.
                compose.onNodeWithText(context.getString(UiR.string.msg_regenerate_title)).assertExists()
                compose.onNodeWithText(context.getString(UiR.string.msg_regenerate_confirm)).performClick()
                compose.waitUntil(10_000) { requests.size == 1 && !activeVm.isStreaming }
                compose.runOnIdle {
                    assertEquals("first" to emptyList<String>(), requests.single())
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    assertEquals(
                        listOf("first"),
                        runBlocking { fixture.repository.loadTurns(id) }.map { it.userText },
                    )
                }
                // История следующего запроса продолжается с перегенерированного хода.
                send("third")
                compose.waitUntil(10_000) { requests.size == 2 }
                assertEquals("third" to listOf("first", "answer"), requests[1])
            }
        }
    }

    @Test fun regenerateAfterFailedGenerationResendsTheMessage() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            withScreen(fixture, null, "Draft") { vm, requests ->
                reply = { flow { throw IllegalStateException("generation failed") } }
                send("first")
                // Ошибка генерации: ход не записан, реплика осиротела в ленте.
                compose.runOnIdle {
                    assertEquals(0, vm.turnStore.size)
                }
                // Повторный запуск успешен: ответ переключается до клика, чтобы
                // повтор не ушёл со старым сбойным ответом.
                reply = { flowOf(ChatChunk.Content("answer"), ChatChunk.Finish("stop")) }
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                // Реплика последняя: подтверждение не требуется.
                compose.onAllNodesWithText(context.getString(UiR.string.msg_regenerate_title)).assertCountEquals(0)
                compose.waitUntil(10_000) { requests.size == 2 && !activeVm.isStreaming }
                compose.runOnIdle {
                    assertEquals("first" to emptyList<String>(), requests[1])
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                }
            }
        }
    }

    @Test fun failedRegenerateRemovalCompletesBothStepsAfterRetryWrites() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            withScreen(fixture, id, "Source") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                fixture.database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_delete BEFORE DELETE ON turns BEGIN SELECT RAISE(ABORT, 'test abort'); END",
                )
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                // Сбой записи: ничего не удалено и не перезапущено —
                // состояние «Повторить запись».
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.runOnIdle {
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    assertEquals(0, requests.size)
                }
                // «Повторить запись» завершает подтверждённое действие целиком:
                // стирание и перезапуск генерации.
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                compose.waitUntil(10_000) { requests.size == 1 && !activeVm.isStreaming }
                compose.runOnIdle {
                    assertEquals("first" to emptyList<String>(), requests.single())
                    assertEquals(listOf("first"), vm.turnStore.map { it.turn.userText })
                    assertEquals("answer", vm.turnStore.single().turn.answer)
                    assertEquals(
                        listOf("first"),
                        runBlocking { fixture.repository.loadTurns(id) }.map { it.userText },
                    )
                }
            }
        }
    }

    @Test fun stopDuringRegenerateRemovesTurnButDoesNotRestartGeneration() = runBlocking {
        GatedExecutor().use { gate ->
            ChatPersistenceFixture(transactionExecutor = gate).use { fixture ->
                val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
                withScreen(fixture, id, "Source") { vm, requests ->
                    val pending = gate.pauseNext()
                    send("first")
                    assertTrue(pending.entered.await(10, TimeUnit.SECONDS))
                    // Ход ещё пишется: повтор встаёт в очередь после записи.
                    compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                        .performClick()
                    compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                    // Кнопка отправки стала кнопкой стопа — стоп отменяет повтор.
                    compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_stop)).performClick()
                    pending.release.countDown()
                    compose.waitUntil(10_000) { vm.turnStore.isEmpty() }
                    // Подтверждённое стирание довелось до конца, генерация не запускалась.
                    compose.runOnIdle {
                        assertEquals(1, requests.size)
                        assertEquals(0, runBlocking { fixture.repository.loadTurns(id) }.size)
                        assertEquals(false, vm.isStreaming)
                        // Мёртвая обёртка не остаётся ручкой стопа/повтора.
                        assertEquals(null, vm.streamJob)
                    }
                }
            }
        }
    }

    @Test fun retryWritesWhileAnotherGenerationRunsSkipsRegenerateWithoutClobber() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            withScreen(fixture, id, "Source") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                fixture.database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_delete BEFORE DELETE ON turns BEGIN SELECT RAISE(ABORT, 'test abort'); END",
                )
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Пока идёт другая генерация, «Повторить запись» доводит стирание,
                // но перезапуск не затирает чужой стрим.
                reply = { flow { awaitCancellation() } }
                compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint)).performTextInput("новый")
                compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
                compose.waitUntil(10_000) { requests.size == 1 }
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                compose.waitUntil(10_000) {
                    vm.turnStore.isEmpty() && runBlocking { fixture.repository.loadTurns(id) }.isEmpty()
                }
                compose.runOnIdle {
                    // Второй запрос не запускался, идущий стрим не затёрт.
                    assertEquals(1, requests.size)
                    assertEquals(true, vm.isStreaming)
                }
                compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_stop)).performClick()
                compose.waitUntil(10_000) { !activeVm.isStreaming }
            }
        }
    }

    @Test fun retryWritesAfterRegenerateFailureKeepsTurnsRecordedLater() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.repository.appendTurn(id, StoredTurn(userText = "first", answer = "answer first"))
            withScreen(fixture, id, "Source") { vm, requests ->
                open("Source")
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                fixture.database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_delete BEFORE DELETE ON turns BEGIN SELECT RAISE(ABORT, 'test abort'); END",
                )
                compose.onAllNodesWithContentDescription(context.getString(UiR.string.msg_actions_menu))[0]
                    .performClick()
                compose.onNodeWithText(context.getString(UiR.string.msg_action_regenerate)).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Пока стирание сбойное, записывается более новый ход.
                send("new")
                compose.runOnIdle {
                    assertEquals(listOf("first", "new"), vm.turnStore.map { it.turn.userText })
                }
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                // Повтор стёр только подтверждённый ход и перезапустил его генерацию.
                compose.waitUntil(10_000) { requests.size == 2 && !activeVm.isStreaming }
                compose.runOnIdle {
                    assertEquals("first" to listOf("new", "answer"), requests[1])
                    assertEquals(listOf("new", "first"), vm.turnStore.map { it.turn.userText })
                }
            }
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
        compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint)).performTextInput(text)
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { !activeVm.isStreaming }
    }

    /** Экран чата с фикстурой БД; в запросах пишется ввод и история модели. */
    private fun withScreen(
        fixture: ChatPersistenceFixture,
        id: String?,
        title: String,
        action: (ChatSessionViewModel, MutableList<Pair<String, List<String>>>) -> Unit,
    ) {
        val previousRepository = checkNotNull(ChatHandle.get())
        val previousNetwork = checkNotNull(NetworkHandle.get())
        val requests = mutableListOf<Pair<String, List<String>>>()
        val vm = ChatSessionViewModel().apply { chatId = id; chatTitle = title; titleLocked = true }
        activeVm = vm
        val visible = mutableStateOf(true)
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean) = reply()
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
                                requests.add(text to history.map { it.content })
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
