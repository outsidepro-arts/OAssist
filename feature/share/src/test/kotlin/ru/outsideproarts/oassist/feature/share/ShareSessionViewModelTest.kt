package ru.outsideproarts.oassist.feature.share

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.*
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.provider.*
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

@OptIn(ExperimentalCoroutinesApi::class)
class ShareSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val storage = Store()
    private val requests = mutableListOf<List<ChatMessage>>()
    private var reply: () -> Flow<ChatChunk> = { flowOf(ChatChunk.Content("answer"), ChatChunk.Finish("stop")) }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean): Flow<ChatChunk> {
                requests.add(messages)
                return reply()
            }
        }
        AgentFactory.register(object : AgentFactory {
            override suspend fun modeConfig(mode: AssistMode) = ModeConfig(mode, modelId = "model")
            override suspend fun providerForMode(mode: AssistMode) = ProviderProfile("provider", "test", ProviderPreset.OPENAI, "https://example.invalid")
            override suspend fun modelClientFor(profile: ProviderProfile): ModelClient = error("No generated titles in this fixture")
            override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode) = AgentLoop(client, diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics)
            override fun clearSessionApprovals(mode: AssistMode) = Unit
            override fun usageTotals() = MutableStateFlow(UsageTotalsState())
            override suspend fun systemPromptFor(mode: AssistMode, screenContext: String?, sharedContent: String?) = RuntimePrompt("system")
        })
    }

    @After fun teardown() {
        owners.forEach { it.clear() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun vm() = ShareSessionViewModel(storage).also { vm ->
        owners.add(ViewModelStore().apply { put("share", vm) })
    }
    private fun summary(id: String) = ChatSummary(id, id, ChatKind.REGULAR, AssistMode.SHARE, 0, 0)
    private fun request(text: String = "question") = ShareAskRequest(text, text, ModelUserInput(text), "content")
    private fun ready(vm: ShareSessionViewModel, id: String? = null) {
        vm.loadPayload { emptyList() }
        if (id == null) vm.newChat() else vm.openChat(summary(id))
        dispatcher.scheduler.runCurrent()
    }

    @Test fun delayedPayloadBlocksModelAndSnapshotMatchesSavedAndReopenedImages() = runTest(dispatcher) {
        val vm = vm()
        val release = CompletableDeferred<List<ImagePart>>()
        vm.newChat()
        vm.loadPayload { release.await() }
        runCurrent()
        vm.ask(request())
        runCurrent()
        assertFalse(vm.canSend)
        assertTrue(requests.isEmpty())
        val images = mutableListOf(ImagePart("aW1hZ2U="))
        release.complete(images)
        runCurrent()
        assertTrue(vm.canSend)
        vm.ask(request())
        images.clear()
        runCurrent()
        val saved = storage.rows.getValue(checkNotNull(vm.chatId)).single()
        assertEquals(listOf(ImagePart("aW1hZ2U=")), requests.single().last().images)
        assertEquals(requests.single().last().images, saved.userImages)
        val reopened = vm()
        ready(reopened, checkNotNull(vm.chatId))
        assertEquals(saved, reopened.turns.single())
        reopened.ask(request("next"))
        runCurrent()
        assertEquals(saved.userImages, requests.last().first { it.role == ChatRole.USER }.images)
    }

    @Test fun recreationKeepsOnePayloadLoadAndOneActiveStream() = runTest(dispatcher) {
        val vm = vm()
        vm.restoreAfterProcessDeath(false, null)
        val payload = CompletableDeferred<List<ImagePart>>()
        var loads = 0
        vm.loadPayload { loads++; payload.await() }
        runCurrent()
        // The new Activity reconnects to the same retained VM.
        vm.loadPayload { loads++; emptyList() }
        payload.complete(emptyList())
        vm.newChat()
        runCurrent()
        val finish = CompletableDeferred<Unit>()
        reply = { flow { emit(ChatChunk.Content("answer")); finish.await(); emit(ChatChunk.Finish("stop")) } }
        vm.ask(request())
        runCurrent()
        vm.restoreAfterProcessDeath(true, "stale")
        assertEquals(1, loads)
        assertTrue(vm.isStreaming)
        assertEquals(1, requests.size)
        finish.complete(Unit)
        runCurrent()
        assertEquals(1, storage.rows.values.sumOf { it.size })
    }

    @Test fun switchCancelsStreamAndWaitsForItsPartialWriteBeforeOpeningB() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        val write = CompletableDeferred<Unit>()
        storage.beforeAppend = { write.await() }
        reply = { flow { emit(ChatChunk.Reasoning("partial A")); awaitCancellation() } }
        vm.ask(request())
        runCurrent()
        vm.openChat(summary("B"))
        runCurrent()
        assertTrue(vm.isChangingConversation)
        assertEquals("A", vm.chatId)
        assertFalse(vm.canSend)
        write.complete(Unit)
        runCurrent()
        assertEquals("B", vm.chatId)
        assertTrue(vm.turns.isEmpty())
        val partial = storage.rows.getValue("A").single()
        assertEquals("", partial.answer)
        assertEquals("partial A", (partial.work.single() as WorkEvent.Thought).text)
        assertTrue(storage.rows.getValue("B").isEmpty())
    }

    @Test fun delayedNonCooperativeLoadCannotPublishAfterNewOpen() = runTest(dispatcher) {
        val vm = vm()
        ready(vm)
        val release = CompletableDeferred<Unit>()
        storage.beforeLoad = { id -> if (id == "A") withContext(NonCancellable) { release.await() } }
        vm.openChat(summary("A"))
        runCurrent()
        vm.openChat(summary("B"))
        runCurrent()
        assertFalse(vm.canSend)
        release.complete(Unit)
        runCurrent()
        assertEquals("B", vm.chatId)
        assertTrue(vm.turns.isEmpty())
        assertFalse(vm.isChangingConversation)
    }

    @Test fun deleteWaitsForPendingCreateAndWriteAndDoesNotResurrectConversation() = runTest(dispatcher) {
        val vm = vm()
        ready(vm)
        val create = CompletableDeferred<Unit>()
        storage.beforeCreate = { create.await() }
        vm.ask(request())
        runCurrent()
        vm.deleteCurrentChat()
        runCurrent()
        assertTrue(vm.isChangingConversation)
        create.complete(Unit)
        runCurrent()
        assertNull(vm.chatId)
        assertTrue(vm.turns.isEmpty())
        assertEquals(listOf("new-1"), storage.deleted)
        assertFalse(storage.rows.containsKey("new-1"))
        assertFalse(vm.isStreaming)
    }

    @Test fun deleteDuringStreamingRecordsThenDeletesOriginalOwner() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        reply = { flow { emit(ChatChunk.Reasoning("partial")); awaitCancellation() } }
        vm.ask(request())
        runCurrent()
        vm.deleteCurrentChat()
        runCurrent()
        assertNull(vm.chatId)
        assertEquals(listOf("A"), storage.deleted)
        assertFalse(storage.rows.containsKey("A"))
        assertFalse(vm.isStreaming)
    }

    @Test fun saveFailureRetainsOwnerAndRetryDoesNotDuplicateSuccessfulPrefix() = runTest(dispatcher) {
        val vm = vm()
        ready(vm)
        vm.autoSave = false
        vm.ask(request("one"))
        runCurrent()
        vm.ask(request("two"))
        runCurrent()
        var fail = true
        storage.beforeAppend = { turn -> if (turn.userText == "two" && fail) error("disk full") }
        vm.saveChat()
        runCurrent()
        assertTrue(vm.saveFailed)
        vm.openChat(summary("B"))
        runCurrent()
        assertEquals("new-1", vm.chatId)
        assertEquals(2, vm.turns.size)
        assertFalse(vm.canSend)
        fail = false
        vm.saveChat()
        runCurrent()
        vm.openChat(summary("B"))
        runCurrent()
        assertEquals("B", vm.chatId)
        assertEquals(listOf("one", "two"), storage.rows.getValue("new-1").map { it.userText })
        assertEquals(1, storage.created)
    }

    @Test fun restoreLoadCannotOverwriteNewConversationAndLoadFailureKeepsOldHistory() = runTest(dispatcher) {
        val vm = vm()
        val release = CompletableDeferred<Unit>()
        storage.beforeLoad = { withContext(NonCancellable) { release.await() } }
        vm.restoreAfterProcessDeath(true, "A")
        runCurrent()
        vm.newChat()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertNull(vm.chatId)
        storage.beforeLoad = { error("read failed") }
        vm.turns.add(StoredTurn(answer = "retained"))
        vm.openChat(summary("B"))
        runCurrent()
        assertNull(vm.chatId)
        assertEquals("retained", vm.turns.single().answer)
        assertFalse(vm.isChangingConversation)
    }

    @Test fun ownerDestructionCancelsStreamButAllowsTerminalSaveToFinish() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        val write = CompletableDeferred<Unit>()
        storage.beforeAppend = { write.await() }
        reply = { flow { emit(ChatChunk.Reasoning("partial")); awaitCancellation() } }
        vm.ask(request())
        runCurrent()
        owners.single().clear()
        runCurrent()
        assertFalse(vm.canSend)
        assertTrue(storage.rows.getValue("A").isEmpty())
        write.complete(Unit)
        runCurrent()
        assertEquals("partial", (storage.rows.getValue("A").single().work.single() as WorkEvent.Thought).text)
        assertFalse(vm.scope.isActive)
    }

    @Test fun lateFinishAfterCancellationCannotAppendToNextOwner() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        val finish = CompletableDeferred<Unit>()
        reply = { flow {
            emit(ChatChunk.Reasoning("partial"))
            withContext(NonCancellable) { finish.await() }
            emit(ChatChunk.Content("late answer"))
            emit(ChatChunk.Finish("stop"))
        } }
        vm.ask(request())
        runCurrent()
        vm.openChat(summary("B"))
        runCurrent()
        assertEquals("A", vm.chatId)
        finish.complete(Unit)
        runCurrent()
        assertEquals("B", vm.chatId)
        assertTrue(vm.turns.isEmpty())
        assertEquals(1, storage.rows.getValue("A").size)
        assertEquals("", storage.rows.getValue("A").single().answer)
    }

    @Test fun earlySetupFailureReleasesStreamingAndPayloadFailureCanRetry() = runTest(dispatcher) {
        val vm = vm()
        vm.loadPayload { error("content read failed") }
        vm.newChat()
        runCurrent()
        assertFalse(vm.payloadReady)
        vm.loadPayload { emptyList() }
        runCurrent()
        assertTrue(vm.payloadReady)
        vm.openChat(summary("A"))
        runCurrent()
        storage.failBind = true
        vm.ask(request())
        runCurrent()
        assertFalse(vm.isStreaming)
        assertTrue(requests.isEmpty())
        assertTrue(vm.canSend)
    }

    private class Store : ShareChatStore {
        val rows = mutableMapOf("A" to mutableListOf<StoredTurn>(), "B" to mutableListOf())
        val deleted = mutableListOf<String>()
        var created = 0
        var failBind = false
        var beforeLoad: suspend (String) -> Unit = {}
        var beforeCreate: suspend () -> Unit = {}
        var beforeAppend: suspend (StoredTurn) -> Unit = {}
        override suspend fun loadTurns(id: String): List<StoredTurn> { beforeLoad(id); return rows[id].orEmpty().toList() }
        override suspend fun title(id: String) = id
        override suspend fun createChat(mode: AssistMode, title: String): String {
            beforeCreate()
            return "new-${++created}".also { rows[it] = mutableListOf() }
        }
        override suspend fun appendTurn(id: String, turn: StoredTurn) { beforeAppend(turn); rows.getValue(id).add(turn) }
        override suspend fun deleteChat(id: String) { deleted.add(id); rows.remove(id) }
        override suspend fun renameChat(id: String, title: String) = Unit
        override fun diagnosticGeneration(id: String) = 0L
        override suspend fun bindDiagnosticOperation(id: String, operation: String, generation: Long?) { if (failBind) error("bind failed") }
    }
}
