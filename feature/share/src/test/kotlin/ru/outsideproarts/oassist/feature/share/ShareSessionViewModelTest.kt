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
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.*
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

@OptIn(ExperimentalCoroutinesApi::class)
class ShareSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val storage = Store()
    private val requests = mutableListOf<List<ChatMessage>>()
    private var reply: () -> Flow<ChatChunk> = { flowOf(ChatChunk.Content("answer"), ChatChunk.Finish("stop")) }
    /** Политика подтверждений тестового агента; дефолт — с разрешённой записью APP_DATA. */
    private var policy: ApprovalPolicy = InMemoryApprovalPolicy().apply {
        setRemembered(ToolGroups.key(ToolCategory.APP_DATA, ToolAccess.WRITE), ApprovalState.ALLOW)
    }

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
            override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode) = AgentLoop(
                client,
                ToolRegistry(
                    listOf(MessagePinTool(), MessageUnpinTool()),
                    diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                ),
                approvalPolicy = policy,
                diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
            )
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
        assertEquals(requests.single().last().images, saved.turn.userImages)
        val reopened = vm()
        ready(reopened, checkNotNull(vm.chatId))
        assertEquals(saved, reopened.turns.single())
        reopened.ask(request("next"))
        runCurrent()
        assertEquals(saved.turn.userImages, requests.last().first { it.role == ChatRole.USER }.images)
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
        assertEquals("", partial.turn.answer)
        assertEquals("partial A", (partial.turn.work.single() as WorkEvent.Thought).text)
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
        // Сбой записи не останавливает диалог (паритет с Chat): отправка доступна.
        assertTrue(vm.canSend)
        fail = false
        vm.saveChat()
        runCurrent()
        vm.openChat(summary("B"))
        runCurrent()
        assertEquals("B", vm.chatId)
        assertEquals(listOf("one", "two"), storage.rows.getValue("new-1").map { it.turn.userText })
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
        vm.turns.add(StoredTurnEntry("retained", StoredTurn(answer = "retained")))
        vm.openChat(summary("B"))
        runCurrent()
        assertNull(vm.chatId)
        assertEquals("retained", vm.turns.single().turn.answer)
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
        assertEquals("partial", (storage.rows.getValue("A").single().turn.work.single() as WorkEvent.Thought).text)
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
        assertEquals("", storage.rows.getValue("A").single().turn.answer)
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

    @Test fun deleteTurnRemovesRowAndMemoryAndRollbackKeepsTarget() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two", "three")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        vm.deleteTurnMessage("id-two")
        runCurrent()
        assertEquals(listOf("one", "three"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one", "three"), storage.rows.getValue("A").map { it.turn.userText })
        assertEquals(listOf("id-two"), storage.deletedTurns)
        vm.rollbackToTurn("id-one")
        runCurrent()
        assertEquals(listOf("one"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one"), storage.rows.getValue("A").map { it.turn.userText })
        assertEquals(listOf("id-two", "id-one"), storage.deletedTurns)
    }

    @Test fun rollbackRunsAfterPendingAppendAndRemovesItFromDatabaseToo() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = mutableListOf(StoredTurnEntry("id-one", StoredTurn(userText = "one")))
        ready(vm, "A")
        val write = CompletableDeferred<Unit>()
        storage.beforeAppend = { write.await() }
        vm.ask(request("two"))
        runCurrent()
        // Стирание встаёт в очередь после незавершённой дозаписи и сносит её.
        vm.rollbackToTurn("id-one")
        runCurrent()
        write.complete(Unit)
        runCurrent()
        assertEquals(listOf("one"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one"), storage.rows.getValue("A").map { it.turn.userText })
        assertEquals(listOf("id-one"), storage.deletedTurns)
    }

    @Test fun deletingUnsavedTurnTouchesOnlyMemory() = runTest(dispatcher) {
        val vm = vm()
        ready(vm)
        vm.autoSave = false
        vm.ask(request("one"))
        runCurrent()
        vm.deleteTurnMessage(vm.turns.single().id)
        runCurrent()
        assertTrue(vm.turns.isEmpty())
        assertTrue(storage.deletedTurns.isEmpty())
        assertTrue(storage.rows.values.all { it.isEmpty() })
    }

    @Test fun failedRemovalKeepsMemoryAndConversationUsable() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        storage.beforeDeleteTurn = { error("disk failure") }
        vm.deleteTurnMessage("id-two")
        runCurrent()
        assertEquals(listOf("one", "two"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one", "two"), storage.rows.getValue("A").map { it.turn.userText })
        // Сбойное стирание — состояние «Повторить» (как очередь в Chat),
        // но диалог остаётся рабочим.
        assertTrue(vm.saveFailed)
        assertTrue(vm.canSend)
        storage.beforeDeleteTurn = {}
        vm.retryWrites()
        runCurrent()
        assertFalse(vm.saveFailed)
        assertEquals(listOf("one"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one"), storage.rows.getValue("A").map { it.turn.userText })
    }

    @Test fun regenerateRemovesTurnAndFollowingAndRunsTheAskAgain() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two", "three")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        val asksBefore = requests.size
        reply = { flowOf(ChatChunk.Content("ответ два"), ChatChunk.Finish("stop")) }

        vm.regenerateTurn("id-two", request("two"))
        runCurrent()

        // Ход и все последующие стёрты; запрос ушёл заново с вводом выбранного
        // хода и записан новым ходом вместо удалённого.
        assertEquals(asksBefore + 1, requests.size)
        assertTrue(requests.last().last().content.contains("\"request\":\"two\""))
        assertFalse(requests.last().any { it.content.contains("three") })
        assertEquals(listOf("one", "two"), storage.rows.getValue("A").map { it.turn.userText })
        assertEquals("ответ два", storage.rows.getValue("A").last().turn.answer)
    }

    @Test fun regenerateActiveResendsTheAskWithoutRecordedTurn() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        reply = { flow { awaitCancellation() } }
        vm.ask(request("первый"))
        runCurrent()
        vm.cancelTurn()
        runCurrent()
        // Вопрос осиротел: ход не записан, реплика осталась в ленте.
        assertEquals("первый", vm.activeUserMsg)
        assertTrue(vm.turns.isEmpty())

        reply = { flowOf(ChatChunk.Content("ответ"), ChatChunk.Finish("stop")) }
        vm.regenerateActive()
        runCurrent()

        assertEquals(2, requests.size)
        assertTrue(requests.last().last().content.contains("\"request\":\"первый\""))
        assertEquals("ответ", vm.turns.single().turn.answer)
        assertNull(vm.activeUserMsg)
    }

    @Test fun failedRegenerateRemovalCompletesBothStepsAfterRetryWrites() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        val asksBefore = requests.size
        storage.beforeDeleteTurn = { error("disk failure") }

        vm.regenerateTurn("id-two", request("two"))
        runCurrent()

        // Сбойное стирание: ничего не удалено и не перезапущено —
        // состояние «Повторить».
        assertEquals(asksBefore, requests.size)
        assertTrue(vm.saveFailed)
        assertEquals(listOf("one", "two"), vm.turns.map { it.turn.userText })

        // «Повторить запись» завершает подтверждённое действие целиком:
        // стирание и перезапуск генерации.
        storage.beforeDeleteTurn = {}
        reply = { flowOf(ChatChunk.Content("ответ два"), ChatChunk.Finish("stop")) }
        vm.retryWrites()
        runCurrent()

        assertEquals(asksBefore + 1, requests.size)
        assertEquals(listOf("one", "two"), vm.turns.map { it.turn.userText })
        assertEquals("ответ два", vm.turns.last().turn.answer)
    }

    @Test fun stopDuringRegenerateRemovesTurnButDoesNotRestartGeneration() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        val asksBefore = requests.size
        val deleteGate = CompletableDeferred<Unit>()
        storage.beforeDeleteTurn = { deleteGate.await() }

        vm.regenerateTurn("id-two", request("two"))
        runCurrent()
        vm.cancelTurn()
        deleteGate.complete(Unit)
        runCurrent()

        // Стоп отменяет генерацию; подтверждённое стирание доводится до конца.
        assertEquals(asksBefore, requests.size)
        assertFalse(vm.isStreaming)
        assertTrue(vm.canSend)
        assertEquals(listOf("one"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one"), storage.rows.getValue("A").map { it.turn.userText })
    }

    @Test fun retryWritesWhileStreamingSkipsPendingRegenerateWithNotice() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        storage.beforeDeleteTurn = { error("disk failure") }
        vm.regenerateTurn("id-two", request("two"))
        runCurrent()
        storage.beforeDeleteTurn = {}
        // Пока идёт другая генерация, «Повторить запись» доводит стирание,
        // но перезапуск пропускается с уведомлением и не затирает чужой стрим.
        reply = { flow { awaitCancellation() } }
        vm.ask(request("новый"))
        runCurrent()
        val toasts = mutableListOf<ShareToast>()
        val collectJob = launch { vm.toasts.collect { toasts.add(it) } }
        runCurrent()

        vm.retryWrites()
        runCurrent()
        collectJob.cancel()

        assertEquals(1, requests.size)
        assertTrue(vm.isStreaming)
        assertEquals(listOf("one"), vm.turns.map { it.turn.userText })
        assertTrue(
            toasts.any {
                it is ShareToast.Res &&
                    it.resId == ru.outsideproarts.oassist.feature.share.R.string.share_regenerate_skipped
            },
        )
    }

    @Test fun retryWritesAfterRegenerateFailureKeepsTurnsRecordedLater() = runTest(dispatcher) {
        val vm = vm()
        storage.rows["A"] = listOf("one", "two")
            .map { StoredTurnEntry("id-$it", StoredTurn(userText = it)) }
            .toMutableList()
        ready(vm, "A")
        storage.beforeDeleteTurn = { error("disk failure") }
        vm.regenerateTurn("id-two", request("two"))
        runCurrent()
        storage.beforeDeleteTurn = {}
        // Пока стирание сбойное, записывается более новый ход: подтверждённое
        // стирание не должно его коснуться.
        reply = { flowOf(ChatChunk.Content("ответ три"), ChatChunk.Finish("stop")) }
        vm.ask(request("three"))
        runCurrent()
        assertEquals(listOf("one", "two", "three"), vm.turns.map { it.turn.userText })
        // Успешная дозапись не прячет «Повторить запись»: подтверждённое
        // стирание ещё не исполнено.
        assertTrue(vm.saveFailed)

        reply = { flowOf(ChatChunk.Content("ответ два"), ChatChunk.Finish("stop")) }
        vm.retryWrites()
        runCurrent()

        // Повтор стёр только подтверждённый ход и перезапустил его генерацию.
        assertEquals(listOf("one", "three", "two"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("one", "three", "two"), storage.rows.getValue("A").map { it.turn.userText })
    }

    @Test fun deferAskShowsWaitingStateLikeChat() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        val deferred = request("первый")

        vm.deferAsk(deferred)
        runCurrent()

        // Вопрос сразу виден в ленте ожидания, стрим «идёт» — как в Chat.
        assertEquals("первый", vm.activeUserMsg)
        assertTrue(vm.isStreaming)
        assertFalse(vm.canSend)
    }

    @Test fun voiceAudioAskRecordsAudioTurnLikeChat() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        val audio = AudioPart("V0FW", durationSec = 2)
        vm.ask(request("первый"))
        runCurrent()

        vm.ask(ShareAskRequest(query = "", userMsg = "", modelInput = ModelUserInput(""), contentDesc = "content", audio = audio))
        runCurrent()

        // Голосовой ход без текста сохраняется и доходит до модели — как в Chat.
        val audioTurn = vm.turns.first { it.turn.userAudio != null }
        assertEquals(audio, audioTurn.turn.userAudio)
        assertTrue(requests.last().any { it.audio != null })
    }

    /**
     * Регрессия: авто-повтор отложенного хода по фронту сети обязан работать
     * (условия наблюдателя и вход повтора не должны взаимно блокироваться).
     */
    @Test fun offlineDeferredAskRetriesWhenNetworkReturns() = runTest(dispatcher) {
        val net = FakeNetwork()
        val previous = NetworkHandle.get()
        NetworkHandle.register(net)
        try {
            val vm = vm()
            ready(vm, "A")
            vm.deferAsk(request("первый"))
            runCurrent()
            assertTrue(vm.isStreaming)
            assertEquals(0, requests.size)

            net.isOnline.value = true
            runCurrent()

            // Фронт online: отложенный ход ушёл повторно, ожидание завершено.
            assertEquals(1, requests.size)
            assertEquals(listOf("первый"), vm.turns.map { it.turn.userText })
            assertFalse(vm.isStreaming)
            assertNull(vm.pendingApproval)
        } finally {
            if (previous != null) NetworkHandle.register(previous) else NetworkHandle.register(FakeNetwork())
        }
    }

    /** Регрессия: ранний обрыв без сети ждёт фронта online и повторяет ход. */
    @Test fun earlyOfflineFailureRetriesOnNetworkFront() = runTest(dispatcher) {
        val net = FakeNetwork()
        val previous = NetworkHandle.get()
        NetworkHandle.register(net)
        try {
            val vm = vm()
            ready(vm, "A")
            reply = { flow { throw ru.outsideproarts.oassist.core.provider.ProviderError.Network("offline") } }
            vm.ask(request("первый"))
            // Внутренние ретраи стрима исчерпываются по виртуальному времени.
            dispatcher.scheduler.advanceUntilIdle()

            // Ранний обрыв без сети: ход ждёт фронта online, состояние не зависает.
            assertTrue(vm.isStreaming)
            val attemptsBeforeRetry = requests.size

            reply = { flowOf(ChatChunk.Content("ответ"), ChatChunk.Finish("stop")) }
            net.isOnline.value = true
            dispatcher.scheduler.advanceUntilIdle()

            // Фронт online: ровно один новый запрос к модели — ход повторён и записан.
            assertEquals(attemptsBeforeRetry + 1, requests.size)
            assertEquals(listOf("первый"), vm.turns.map { it.turn.userText })
            assertFalse(vm.isStreaming)
        } finally {
            if (previous != null) NetworkHandle.register(previous) else NetworkHandle.register(FakeNetwork())
        }
    }

    /** Регрессия: «Стоп» в ожидании сети снимает состояние, а не зависает. */
    @Test fun stopClearsWaitingStateAfterEarlyOfflineFailure() = runTest(dispatcher) {
        val net = FakeNetwork()
        val previous = NetworkHandle.get()
        NetworkHandle.register(net)
        try {
            val vm = vm()
            ready(vm, "A")
            reply = { flow { throw ru.outsideproarts.oassist.core.provider.ProviderError.Network("offline") } }
            vm.ask(request("первый"))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.isStreaming)

            vm.cancelTurn()
            runCurrent()

            assertFalse(vm.isStreaming)
            assertTrue(vm.canSend)
            assertTrue(vm.turns.isEmpty())
        } finally {
            if (previous != null) NetworkHandle.register(previous) else NetworkHandle.register(FakeNetwork())
        }
    }

    @Test fun retryWritesRetriesRemovalWithoutMaskingFailedAppend() = runTest(dispatcher) {
        val vm = vm()
        ready(vm)
        vm.autoSave = false
        listOf("one", "two", "three").forEach { text -> vm.ask(request(text)); runCurrent() }
        var failAppend = true
        storage.beforeAppend = { turn -> if (turn.userText == "two" && failAppend) error("disk full") }
        vm.saveChat()
        runCurrent()
        assertTrue(vm.saveFailed)

        storage.beforeDeleteTurn = { error("disk failure") }
        vm.deleteTurnMessage(vm.turns.first().id)
        runCurrent()
        assertTrue(vm.saveFailed)
        assertEquals(listOf("one", "two", "three"), vm.turns.map { it.turn.userText })

        failAppend = false
        storage.beforeDeleteTurn = {}
        vm.retryWrites()
        runCurrent()

        // «Повторить» повторяет и стирание, и сбойную дозапись: ход не теряется.
        assertFalse(vm.saveFailed)
        assertEquals(listOf("two", "three"), vm.turns.map { it.turn.userText })
        assertEquals(listOf("two", "three"), storage.rows.getValue("new-1").map { it.turn.userText })
    }

    @Test fun sharedContentRidesContextOnceAndQuestionIsRaw() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        vm.ask(request("первый вопрос"))
        runCurrent()
        vm.ask(request("второй вопрос"))
        runCurrent()

        // Вопрос уходит сырым текстом (как в Chat); общий контент — один раз
        // контекстом запроса, исторические конверты не несут его повторов.
        val body = requests.last().joinToString("\n") { it.content }
        assertTrue("вопрос не сырой: $body", body.contains("\"request\":\"второй вопрос\""))
        assertEquals(1, Regex("\"SHARED_CONTENT\"").findAll(body).count())
    }

    @Test fun pinOfPastTurnUpdatesMemoryAndStorage_andPinOfCurrentRidesItsRecord() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        vm.ask(request("first"))
        runCurrent()
        val pastId = vm.turns.single().id

        var round = 0
        reply = {
            if (round++ == 0) flowOf(
                ChatChunk.ToolCall("c1", "message_pin", """{"message_id":"$pastId:0","note":"референс"}"""),
                ChatChunk.ToolCall("c2", "message_pin", """{"message_id":"current:0","note":"текущий"}"""),
                ChatChunk.Finish("tool_calls"),
            ) else flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
        }
        vm.ask(request("second"))
        runCurrent()

        // Прошлый ход закреплён и в ленте (бюджеты следующих ходов), и в хранилище.
        assertEquals(MessagePin("референс"), vm.turns.first { it.id == pastId }.turn.userPin)
        // Текущий ход закреплён флагом своей реплики при записи.
        assertEquals(MessagePin("текущий"), vm.turns.first { it.turn.userText == "second" }.turn.userPin)
        val rows = storage.rows.getValue("A")
        assertEquals(MessagePin("референс"), rows.first { it.id == pastId }.turn.userPin)
        assertEquals(MessagePin("текущий"), rows.first { it.turn.userText == "second" }.turn.userPin)
    }

    /**
     * Регрессия дефекта устройства: в Share закрепление запрашивается у
     * пользователя (approval gate), а не отклоняется молча — иначе флаг не
     * ставится и фото референса вырезается бюджетом на длинной сессии.
     */
    @Test fun pinRequestWaitsForUserDecisionInsteadOfAutoDeny() = runTest(dispatcher) {
        policy = InMemoryApprovalPolicy()
        val vm = vm()
        ready(vm, "A")
        var round = 0
        reply = {
            if (round++ == 0) flowOf(
                ChatChunk.ToolCall("c1", "message_pin", """{"message_id":"current:0"}"""),
                ChatChunk.Finish("tool_calls"),
            ) else flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
        }
        vm.ask(request("первый"))
        runCurrent()

        // Share ждёт решения пользователя: второй запрос к модели не ушёл.
        assertEquals(1, requests.size)
        assertTrue(vm.isStreaming)
        assertNotNull(vm.pendingApproval)

        vm.resolveApproval(ApprovalDecision.ALLOW_ONCE)
        runCurrent()

        // После разрешения закрепление выполняется и ход записывается с флагом.
        assertEquals(2, requests.size)
        assertEquals(MessagePin(), vm.turns.single().turn.userPin)
    }

    @Test fun deniedPinIsNotApplied() = runTest(dispatcher) {
        policy = InMemoryApprovalPolicy()
        val vm = vm()
        ready(vm, "A")
        var round = 0
        reply = {
            if (round++ == 0) flowOf(
                ChatChunk.ToolCall("c1", "message_pin", """{"message_id":"current:0"}"""),
                ChatChunk.Finish("tool_calls"),
            ) else flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
        }
        vm.ask(request("первый"))
        runCurrent()
        assertNotNull(vm.pendingApproval)

        vm.resolveApproval(ApprovalDecision.DENY)
        runCurrent()

        assertNull(vm.pendingApproval)
        assertNull(vm.turns.single().turn.userPin)
    }

    @Test fun pendingApprovalIsClearedWhenConversationChanges() = runTest(dispatcher) {
        policy = InMemoryApprovalPolicy()
        val vm = vm()
        ready(vm, "A")
        reply = {
            flowOf(
                ChatChunk.ToolCall("c1", "message_pin", """{"message_id":"current:0"}"""),
                ChatChunk.Finish("tool_calls"),
            )
        }
        vm.ask(request("первый"))
        runCurrent()
        assertNotNull(vm.pendingApproval)

        vm.openChat(summary("B"))
        runCurrent()

        assertNull(vm.pendingApproval)
    }

    @Test fun cancelTurnStopsStreamWithoutRecordingEmptyTurn() = runTest(dispatcher) {
        val vm = vm()
        ready(vm, "A")
        reply = { flow { awaitCancellation() } }
        vm.ask(request("первый"))
        runCurrent()
        assertTrue(vm.isStreaming)

        vm.cancelTurn()
        runCurrent()

        assertFalse(vm.isStreaming)
        assertTrue(vm.turns.isEmpty())
    }

    @Test fun pinnedReferenceImageReachesLaterRequests() = runTest(dispatcher) {
        val vm = vm()
        val image = ImagePart("aW1hZ2U=")
        vm.newChat()
        vm.loadPayload { listOf(image) }
        runCurrent()
        vm.ask(request("первый"))
        runCurrent()
        val pastId = vm.turns.single().id

        var round = 0
        reply = {
            if (round++ == 0) flowOf(
                ChatChunk.ToolCall("c1", "message_pin", """{"message_id":"$pastId:0","note":"референс"}"""),
                ChatChunk.Finish("tool_calls"),
            ) else flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
        }
        vm.ask(request("второй"))
        runCurrent()

        reply = { flowOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")) }
        vm.ask(request("третий"))
        runCurrent()

        // Закреплённое фото референса доставляется в текущем сообщении каждого
        // следующего запроса (свежая позиция — в поле зрения модели) с указанием
        // источника и заметки закрепления рядом с образом; историческое сообщение
        // остаётся с меткой.
        val current = requests.last().last {
            it.role == ChatRole.USER && it.content.contains("current_user_input")
        }
        assertNotNull("закреплённое фото не дошло до запроса", current.images.firstOrNull { it == image })
        assertTrue("источник референса не указан", current.content.contains(pastId))
        assertTrue("заметка закрепления не доставлена с референсом", current.content.contains("\"note\":\"референс\""))
        assertTrue(
            "историческое сообщение потеряло метку закрепления",
            requests.last().any { it.content.contains(pastId) && it.content.contains("\"pinned\"") },
        )
    }

    private class FakeNetwork : ru.outsideproarts.oassist.core.net.NetworkHandle {
        override val isOnline = MutableStateFlow(false)
    }

    private class Store : ShareChatStore {
        val rows = mutableMapOf("A" to mutableListOf<StoredTurnEntry>(), "B" to mutableListOf())
        val deleted = mutableListOf<String>()
        /** Ходы, переданные в deleteTurn/deleteTurnsAfter (цель стирания). */
        val deletedTurns = mutableListOf<String>()
        var created = 0
        var failBind = false
        var beforeLoad: suspend (String) -> Unit = {}
        var beforeCreate: suspend () -> Unit = {}
        var beforeAppend: suspend (StoredTurn) -> Unit = {}
        var beforeDeleteTurn: suspend (String) -> Unit = {}
        override suspend fun loadTurnEntries(id: String): List<StoredTurnEntry> {
            beforeLoad(id)
            return rows[id].orEmpty().toList()
        }
        override suspend fun title(id: String) = id
        override suspend fun createChat(mode: AssistMode, title: String): String {
            beforeCreate()
            return "new-${++created}".also { rows[it] = mutableListOf() }
        }
        override suspend fun appendTurn(id: String, turn: StoredTurn, turnId: String) {
            beforeAppend(turn)
            rows.getValue(id).add(StoredTurnEntry(turnId, turn))
        }
        override suspend fun deleteTurn(id: String, turnId: String) {
            beforeDeleteTurn(turnId)
            deletedTurns.add(turnId)
            rows[id]?.removeAll { it.id == turnId }
        }
        override suspend fun deleteTurnsAfter(id: String, turnId: String) {
            beforeDeleteTurn(turnId)
            deletedTurns.add(turnId)
            val list = rows[id] ?: return
            val index = list.indexOfFirst { it.id == turnId }
            if (index >= 0) while (list.size > index + 1) list.removeAt(list.size - 1)
        }
        override suspend fun deleteChat(id: String) { deleted.add(id); rows.remove(id) }
        override suspend fun renameChat(id: String, title: String) = Unit
        override suspend fun setPin(id: String, turnId: String, messageIndex: Int, pinned: Boolean, note: String?): Boolean {
            val entry = rows[id]?.indexOfFirst { it.id == turnId } ?: return false
            if (entry < 0) return false
            val list = rows.getValue(id)
            list[entry] = list[entry].copy(turn = list[entry].turn.withMessagePin(messageIndex, pinned, note))
            return true
        }
        override fun diagnosticGeneration(id: String) = 0L
        override suspend fun bindDiagnosticOperation(id: String, operation: String, generation: Long?) { if (failBind) error("bind failed") }
    }
}
