package ru.outsideproarts.oassist.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatKind
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.CaptureMode
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.BtInputDecision
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.ChatSessionViewModel
import ru.outsideproarts.oassist.feature.chat.R as ChatR
import ru.outsideproarts.oassist.core.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class ChatPersistenceScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var activeVm: ChatSessionViewModel

    @Test fun reopeningWaitsForSavedHistoryBeforeTheNextModelRequest() = runBlocking {
        GatedExecutor().use { gate ->
            ChatPersistenceFixture(transactionExecutor = gate).use { fixture ->
                val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
                withScreen(fixture, id, "Source", gate::releaseAll) { vm, requests ->
                    val pending = gate.pauseNext()
                    send("first")
                    assertTrue(pending.entered.await(10, TimeUnit.SECONDS))
                    open("Source")
                    compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint)).performTextInput("second")
                    compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
                    compose.runOnIdle {
                        assertEquals("second", vm.input.text)
                        assertEquals(1, requests.size)
                    }
                    pending.release.countDown()
                    try {
                        compose.waitUntil(10_000) { !vm.isLoadingHistory && vm.turnStore.map { it.userText } == listOf("first") }
                    } catch (error: Throwable) {
                        throw AssertionError("id=${vm.chatId}, loading=${vm.isLoadingHistory}, turns=${vm.turnStore.map { it.userText }}, db=${runBlocking { fixture.repository.loadTurns(id).map { it.userText } }}", error)
                    }
                    compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
                    compose.waitUntil(10_000) { requests.size == 2 }
                    assertEquals(listOf("first", "answer"), requests.last().map { it.content })
                }
            }
        }
    }

    @Test fun lateHistoryCannotPopulateAnotherOpenedChat() = runBlocking {
        GatedExecutor().use { gate ->
            ChatPersistenceFixture(queryExecutor = gate).use { fixture ->
                val a = fixture.repository.createChat(AssistMode.CHAT, "Source")
                val b = fixture.repository.createChat(AssistMode.CHAT, "Target")
                fixture.repository.appendTurn(a, StoredTurn(userText = "from A", answer = "answer A"))
                fixture.repository.appendTurn(b, StoredTurn(userText = "from B", answer = "answer B"))
                withScreen(fixture, null, "", gate::releaseAll) { vm, _ ->
                    val pending = gate.pauseNext()
                    open("Source")
                    assertTrue(pending.entered.await(10, TimeUnit.SECONDS))
                    open("Target")
                    pending.release.countDown()
                    compose.waitUntil(10_000) { vm.turnStore.any { it.userText == "from B" } }
                    compose.runOnIdle {
                        assertEquals(b, vm.chatId)
                        assertEquals(listOf("from B"), vm.turnStore.map { it.userText })
                    }
                }
            }
        }
    }

    @Test fun saveAndContinueDoesNotChangeTheTargetOrItsMode() = runBlocking {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
            ChatPersistenceFixture().use { fixture ->
                fixture.repository.replaceAssistantTurns(emptyList())
                val target = ChatRepository.ASSISTANT_CHAT_ID
                fixture.repository.onDiagnosticBind = { _, _ ->
                    entered.countDown()
                    check(resume.await(15, TimeUnit.SECONDS))
                }
                withScreen(fixture, null, "Source", { resume.countDown() }) { vm, _ ->
                    compose.runOnIdle { vm.autoSaveChats = false }
                    send("original")
                    compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_open_list)).performClick()
                    chatRow(context.getString(UiR.string.chats_assistant_title)).performClick()
                    compose.onNodeWithText(context.getString(UiR.string.chats_unsaved_save)).performClick()
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    try {
                        compose.waitUntil(10_000) { vm.chatId == target }
                    } catch (error: Throwable) {
                        throw AssertionError("manual save: current=${vm.chatId}, kind=${vm.chatKind}, loading=${vm.isLoadingHistory}, turns=${vm.turnStore.map { it.userText }}", error)
                    }
                    resume.countDown()
                    compose.waitUntil(10_000) { runBlocking { fixture.repository.observeChats().first().size == 2 } }
                    // Wait for the source's full save, not merely the chat row insertion.
                    val source = runBlocking { fixture.repository.observeChats().first().single { it.id != target } }
                    compose.waitUntil(10_000) { runBlocking { fixture.repository.loadTurns(source.id).size == 1 } }
                    compose.runOnIdle {
                        assertEquals(target, vm.chatId)
                        assertEquals(ChatKind.ASSISTANT, vm.chatKind)
                    }
                    runBlocking {
                        assertEquals(AssistMode.CHAT, fixture.repository.getChat(source.id)!!.mode)
                        assertEquals(listOf("original"), fixture.repository.loadTurns(source.id).map { it.userText })
                        assertTrue(fixture.repository.loadTurns(target).isEmpty())
                    }
                }
            }
    }

    @Test fun failedAppendRetainsBothTurnsAndRetriesWithoutDuplication() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Source")
            fixture.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_turn BEFORE INSERT ON turns BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END",
            )
            withScreen(fixture, id, "Source") { vm, _ ->
                send("first")
                compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry)).fetchSemanticsNodes().isNotEmpty() }
                send("second")
                assertTrue(runBlocking { fixture.repository.loadTurns(id).isEmpty() })
                assertEquals(listOf("first", "second"), vm.turnStore.map { it.userText })
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_turn")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                compose.waitUntil(10_000) { runBlocking { fixture.repository.loadTurns(id).size == 2 } }
                assertEquals(listOf("first", "second"), runBlocking { fixture.repository.loadTurns(id).map { it.userText } })
            }
        }
    }

    @Test fun failedReadKeepsTheCurrentHistoryAndCanBeRetried() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val source = fixture.repository.createChat(AssistMode.CHAT, "Source")
            val target = fixture.repository.createChat(AssistMode.CHAT, "Target")
            fixture.repository.appendTurn(source, StoredTurn(userText = "source turn"))
            fixture.repository.appendTurn(target, StoredTurn(userText = "target turn"))
            withScreen(fixture, null, "") { vm, _ ->
                open("Source")
                compose.waitUntil(10_000) { !vm.isLoadingHistory && vm.chatId == source }
                val db = fixture.database.openHelper.writableDatabase
                db.execSQL("ALTER TABLE turns RENAME TO unavailable_turns")
                try {
                    open("Target")
                    compose.waitUntil(10_000) { !vm.isLoadingHistory }
                    compose.runOnIdle {
                        assertEquals(source, vm.chatId)
                        assertEquals(listOf("source turn"), vm.turnStore.map { it.userText })
                    }
                } finally {
                    db.execSQL("ALTER TABLE unavailable_turns RENAME TO turns")
                }
                open("Target")
                compose.waitUntil(10_000) { !vm.isLoadingHistory && vm.chatId == target }
                assertEquals(listOf("target turn"), vm.turnStore.map { it.userText })
            }
        }
    }

    @Test fun partiallySavedManualChatRetriesOnlyTheFailedAndFollowingTurns() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val target = fixture.repository.createChat(AssistMode.CHAT, "Target")
            withScreen(fixture, null, "Source") { vm, _ ->
                compose.runOnIdle { vm.autoSaveChats = false }
                send("first")
                send("second")
                send("third")
                val db = fixture.database.openHelper.writableDatabase
                db.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON turns WHEN NEW.payload LIKE '%second%' BEGIN SELECT RAISE(ABORT, 'fixture second turn failure'); END")
                compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_open_list)).performClick()
                chatRow("Target").performClick()
                compose.onNodeWithText(context.getString(UiR.string.chats_unsaved_save)).performClick()
                compose.waitUntil(10_000) { vm.chatId == target && compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry)).fetchSemanticsNodes().isNotEmpty() }
                val source = runBlocking { fixture.repository.observeChats().first().single { it.id != target } }
                assertEquals(listOf("first"), runBlocking { fixture.repository.loadTurns(source.id).map { it.userText } })
                db.execSQL("DROP TRIGGER fail_second")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                compose.waitUntil(10_000) { runBlocking { fixture.repository.loadTurns(source.id).size == 3 } }
                assertEquals(listOf("first", "second", "third"), runBlocking { fixture.repository.loadTurns(source.id).map { it.userText } })
                assertEquals(2, runBlocking { fixture.repository.observeChats().first().size })
                compose.runOnIdle {
                    assertEquals(target, vm.chatId)
                    assertTrue(vm.turnStore.isEmpty())
                }
            }
        }
    }

    @Test fun failedCreationRetriesOneChatAndAllPendingTurns() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            fixture.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_chat BEFORE INSERT ON chats BEGIN SELECT RAISE(ABORT, 'fixture create failure'); END",
            )
            withScreen(fixture, null, "Source") { vm, _ ->
                compose.runOnIdle { vm.autoSaveChats = true }
                send("first")
                compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(ChatR.string.chat_save_failed_retry)).fetchSemanticsNodes().isNotEmpty() }
                send("second")
                assertEquals(null, vm.chatId)
                assertTrue(runBlocking { fixture.repository.observeChats().first().isEmpty() })
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_chat")
                compose.onNodeWithText(context.getString(ChatR.string.chat_save_failed_retry)).performClick()
                compose.waitUntil(10_000) { vm.chatId != null && runBlocking { fixture.repository.loadTurns(vm.chatId!!).size == 2 } }
                assertEquals(1, runBlocking { fixture.repository.observeChats().first().size })
                assertEquals(listOf("first", "second"), runBlocking { fixture.repository.loadTurns(vm.chatId!!).map { it.userText } })
            }
        }
    }

    @Test fun deletingPublishedChatBeforeFirstAppendResetsItsUnsavedScreen() = runBlocking {
        GatedExecutor().use { gate ->
            ChatPersistenceFixture(transactionExecutor = gate).use { fixture ->
                val blocked = AtomicReference<GatedExecutor.Gate?>()
                fixture.repository.onDiagnosticBind = { _, _ ->
                    if (blocked.get() == null) blocked.set(gate.pauseNext())
                }
                withScreen(fixture, null, "Source", gate::releaseAll) { vm, _ ->
                    compose.runOnIdle { vm.autoSaveChats = true }
                    send("first")
                    compose.waitUntil(10_000) { blocked.get() != null }
                    assertTrue(blocked.get()!!.entered.await(10, TimeUnit.SECONDS))
                    assertEquals(null, vm.chatId)
                    compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_open_list)).performClick()
                    deleteFromDrawer("Source")
                    blocked.get()!!.release.countDown()
                    compose.waitUntil(10_000) { runBlocking { fixture.repository.observeChats().first().isEmpty() } }
                    compose.runOnIdle {
                        assertEquals(null, vm.chatId)
                        assertTrue(vm.turnStore.isEmpty())
                        assertTrue(vm.messages.isEmpty())
                    }
                }
            }
        }
    }

    @Test fun deletingTheLoadingTargetCannotReopenItOnLateRead() = runBlocking {
        GatedExecutor().use { gate ->
            ChatPersistenceFixture(queryExecutor = gate).use { fixture ->
                val source = fixture.repository.createChat(AssistMode.CHAT, "Source")
                val target = fixture.repository.createChat(AssistMode.CHAT, "Target")
                fixture.repository.appendTurn(target, StoredTurn(userText = "target turn"))
                withScreen(fixture, source, "Source", gate::releaseAll) { vm, _ ->
                    val pending = gate.pauseNext()
                    open("Target")
                    assertTrue(pending.entered.await(10, TimeUnit.SECONDS))
                    compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_open_list)).performClick()
                    deleteFromDrawer("Target")
                    pending.release.countDown()
                    compose.waitUntil(10_000) { runBlocking { fixture.repository.getChat(target) == null } }
                    compose.runOnIdle {
                        assertEquals(source, vm.chatId)
                        assertTrue(vm.turnStore.isEmpty())
                        assertTrue(!vm.isLoadingHistory)
                    }
                }
            }
        }
    }

    private fun deleteFromDrawer(title: String) {
        val titleY = chatRow(title).fetchSemanticsNode().boundsInRoot.center.y
        compose.onNode(hasContentDescription(context.getString(UiR.string.chats_menu)) and SemanticsMatcher("beside the chat title") {
            it.boundsInRoot.top <= titleY && it.boundsInRoot.bottom >= titleY
        }).performClick()
        compose.onNodeWithText(context.getString(UiR.string.chats_action_delete)).performClick()
        compose.onNodeWithText(context.getString(UiR.string.chats_delete_confirm)).performClick()
    }

    @Test fun recognitionFinishingDuringHistoryLoadKeepsItsTextAsDraft() = runBlocking {
        val original = checkNotNull(VoiceHandle.get())
        val voice = DelayedVoice(original)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        VoiceHandle.register(voice)
        try {
            GatedExecutor().use { gate ->
                ChatPersistenceFixture(queryExecutor = gate).use { fixture ->
                    val target = fixture.repository.createChat(AssistMode.CHAT, "Target")
                    withScreen(fixture, null, "", gate::releaseAll) { vm, requests ->
                        compose.onNode(hasContentDescription(context.getString(ChatR.string.chat_mic), substring = true)).performClick()
                        compose.waitUntil(10_000) { voice.listener != null }
                        val pending = gate.pauseNext()
                        open("Target")
                        assertTrue(pending.entered.await(10, TimeUnit.SECONDS))
                        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_stop)).performClick()
                        compose.runOnIdle { assertEquals(1, voice.stops) }
                        compose.runOnIdle {
                            voice.state.value = CaptureState.Idle
                            checkNotNull(voice.listener).onFinal("recognized draft")
                        }
                        compose.runOnIdle {
                            assertEquals("recognized draft", vm.input.text)
                            assertTrue(requests.isEmpty())
                        }
                        pending.release.countDown()
                        compose.waitUntil(10_000) { !vm.isLoadingHistory && vm.chatId == target }
                        assertEquals("recognized draft", vm.input.text)
                    }
                }
            }
        } finally {
            VoiceHandle.register(original)
        }
    }

    private class DelayedVoice(original: VoiceHandle) : VoiceHandle by original {
        val state = MutableStateFlow<CaptureState>(CaptureState.Idle)
        var listener: SttListener? = null
        var stops = 0
        override fun captureState() = state
        override fun isSttAvailable(recognizerComponent: String?) = true
        override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
        override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): CaptureError? {
            this.listener = listener
            state.value = CaptureState.Listening(CaptureMode.STT, 0L)
            return null
        }
        override fun cancelStt() { state.value = CaptureState.Idle }
        override fun stopStt() { stops += 1; state.value = CaptureState.Idle }
    }

    private fun open(title: String) {
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_open_list)).performClick()
        chatRow(title).performClick()
    }

    private fun chatRow(title: String) = compose.onNode(hasText(title) and SemanticsMatcher("chat summary with its date") {
        (it.config.getOrNull(SemanticsProperties.Text)?.size ?: 0) > 1
    })

    private fun send(text: String) {
        compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint)).performTextInput(text)
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
                        sendMessage = { _, text, history, _, callback ->
                            requests.add(history)
                            val agent = AgentLoop(client, diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics)
                            AgentSession(agent.stream("fixture", null, history, text, onTurnMessages = callback), agent)
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

    private class GatedExecutor : java.util.concurrent.Executor, AutoCloseable {
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
