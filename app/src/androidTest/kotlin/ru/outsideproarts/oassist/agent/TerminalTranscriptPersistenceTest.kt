package ru.outsideproarts.oassist.agent

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.outsideproarts.oassist.core.agent.*
import ru.outsideproarts.oassist.core.data.chat.*
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.data.skill.SkillStore
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.ChatSessionViewModel
import ru.outsideproarts.oassist.feature.share.ShareAskRequest
import ru.outsideproarts.oassist.feature.share.ShareSessionViewModel
import ru.outsideproarts.oassist.feature.share.ShareActivity
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.SttListener
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real consumers, on-disk Room reopen and real provider serialization at the HTTP engine boundary. */
@RunWith(Parameterized::class)
class TerminalTranscriptPersistenceTest(private val mode: AssistMode, private val ending: String) {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun externalActionTerminalTranscriptReopensIntoNextPost() = runBlocking {
        val previousFactory = AgentFactory.get()
        val previousRepository = checkNotNull(ChatHandle.get())
        val previousNetwork = checkNotNull(NetworkHandle.get())
        val previousVoice = checkNotNull(VoiceHandle.get())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val sttStarted = CompletableDeferred<Unit>()
        val name = "terminal-${UUID.randomUUID()}.db"
        val mediaRoot = File(context.cacheDir, name)
        val preflightEntered = java.util.concurrent.CountDownLatch(1)
        val releasePreflight = java.util.concurrent.CountDownLatch(1)
        val insertAttempts = AtomicInteger()
        val replacementPost = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        fun intercept(db: androidx.sqlite.db.SupportSQLiteDatabase): androidx.sqlite.db.SupportSQLiteDatabase =
            object : androidx.sqlite.db.SupportSQLiteDatabase by db {
                override fun compileStatement(sql: String): androidx.sqlite.db.SupportSQLiteStatement {
                    val statement = db.compileStatement(sql)
                    if (!sql.trimStart().startsWith("INSERT", ignoreCase = true) || !sql.contains("turns")) return statement
                    fun beforeExecute() {
                        if (insertAttempts.incrementAndGet() == 2) {
                            preflightEntered.countDown()
                            check(releasePreflight.await(20, java.util.concurrent.TimeUnit.SECONDS))
                            throw android.database.sqlite.SQLiteException("controlled preflight failure")
                        }
                    }
                    return object : androidx.sqlite.db.SupportSQLiteStatement by statement {
                        override fun execute() { beforeExecute(); statement.execute() }
                        override fun executeInsert(): Long { beforeExecute(); return statement.executeInsert() }
                    }
                }
            }
        fun open() = Room.databaseBuilder(context, OAssistDatabase::class.java, name).apply {
            if (ending == "saveReplace") openHelperFactory { configuration ->
                val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(configuration)
                object : androidx.sqlite.db.SupportSQLiteOpenHelper by helper {
                    override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase get() = intercept(helper.writableDatabase)
                    override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase get() = intercept(helper.readableDatabase)
                }
            }
        }.build()
        var database = open()
        var repository = ChatRepository(database, ChatMediaStore(mediaRoot))
        val effects = AtomicInteger()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val requestTypes = CopyOnWriteArrayList<String>()
        val secondPost = CompletableDeferred<Unit>()
        val visible = mutableStateOf<ChatSessionViewModel?>(null)
        val chatOwners = mutableListOf<ChatSessionViewModel>()
        val shareOwners = mutableListOf<ShareSessionViewModel>()
        val stores = mutableMapOf<ChatSessionViewModel, ViewModelStore>()
        var shareActivity: ActivityScenario<ShareActivity>? = null
        val firstBlocks = Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"native+/="},{"type":"text","text":"Cannot undo automatically."},{"type":"tool_use","id":"external","name":"effect","input":{}},{"type":"tool_use","id":"read-skill","name":"skill_get","input":{"name":"report"}}]""").jsonArray
        val finalResponse = sse(Json.parseToJsonElement("""[{"type":"text","text":"done"}]""").jsonArray, notice = true)
        val engine = SseEngine(MockEngineConfig().apply { requestHandlers.add { request ->
            requestTypes += request.body.javaClass.name
            assertEquals(HttpMethod.Post, request.method)
            requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val body = when (requests.size) {
                1 -> sse(firstBlocks)
                2 -> {
                    secondPost.complete(Unit)
                    when (ending) {
                        "cancel", "destroy" -> awaitCancellation()
                        "provider" -> "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fixture provider error\"}}\n\n"
                        "prefix", "saveRetry", "saveReplace" -> "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"thinking prefix binding mismatch\"}}\n\n"
                        else -> finalResponse
                    }
                }
                else -> {
                    if (ending == "saveReplace" && requests.size == 3) {
                        replacementPost.complete(Unit)
                        releaseReplacement.await()
                    }
                    finalResponse
                }
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        } })
        val http = HttpClient(engine) {
            install(SSE)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
            expectSuccess = true
        }
        val profile = ProviderProfile("terminal", "terminal", ProviderPreset.CUSTOM, "https://api.anthropic.com/v1", protocol = ModelProtocol.ANTHROPIC_MESSAGES)
        val client = ModelClient.create(Diagnostics, profile, EmptySecrets, http,
            modelInfo = { ModelInfo(it, anthropicThinking = AnthropicThinkingCapabilities(true, false)) })
        val effect = object : Tool {
            override val groupKey = ToolGroups.key(ToolCategory.SCREEN_UI, ToolAccess.WRITE)
            override val schema = ToolSchema("effect", "external effect", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject): ToolResult {
                effects.incrementAndGet()
                return ToolResult(true, "external action committed")
            }
        }
        val skills = object : SkillStore {
            override suspend fun list() = listOf(SkillInfo("report", "Report", "Owner procedure"))
            override suspend fun get(name: String) = list().single()
            override suspend fun upsert(name: String, title: String, body: String) = Unit
            override suspend fun setEnabled(name: String, enabled: Boolean) = Unit
            override suspend fun delete(name: String) = Unit
        }
        val factory = object : AgentFactory {
            override suspend fun modeConfig(mode: AssistMode) = ModeConfig(mode, modelId = "alias", soundEnabled = false,
                assistant = AssistantExtras(autoSpeak = false, showOverlay = ending == "drop"))
            override suspend fun providerForMode(mode: AssistMode) = profile
            override suspend fun modelClientFor(profile: ProviderProfile) = client
            override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode) = AgentLoop(client,
                ToolRegistry(listOf(effect, SkillGetTool(skills)), diagnostics = Diagnostics),
                approvalPolicy = InMemoryApprovalPolicy().apply { applyPreset(AskPreset.ALLOW_ALL) },
                sessionScope = mode.name, diagnostics = Diagnostics)
            override fun clearSessionApprovals(mode: AssistMode) = Unit
            override fun usageTotals() = MutableStateFlow(UsageTotalsState())
            override suspend fun systemPromptFor(mode: AssistMode, screenContext: String?, sharedContent: String?) = RuntimePrompt("system")
        }
        fun chat(id: String, stored: List<StoredTurn> = emptyList()) = ChatSessionViewModel().apply {
            chatId = id; chatTitle = "Terminal"; titleLocked = true
            chatKind = if (mode == AssistMode.ASSISTANT) ChatKind.ASSISTANT else ChatKind.REGULAR
            turnStore.addAll(stored)
            chatOwners += this
            stores[this] = ViewModelStore().also { it.put("chat", this) }
        }
        fun sendChat(text: String) {
            compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.feature.chat.R.string.chat_input_hint)).performTextInput(text)
            compose.onNodeWithContentDescription(context.getString(ru.outsideproarts.oassist.feature.chat.R.string.chat_send)).performClick()
        }
        fun share(id: String): ShareSessionViewModel = ShareSessionViewModel().also { vm ->
            shareOwners += vm
            compose.runOnIdle {
                vm.loadPayload { emptyList() }
                vm.openChat(ChatSummary(id, "Terminal", ChatKind.REGULAR, AssistMode.SHARE, 0, 0))
            }
            compose.waitUntil(10_000) { vm.canSend }
        }
        fun askShare(vm: ShareSessionViewModel, text: String) = compose.runOnIdle {
            vm.ask(ShareAskRequest(text, text, ModelUserInput(text), "content"))
        }
        val serviceIntent = Intent(context, AssistantOverlayService::class.java)
        fun submitService(text: String) {
            val intent = Intent(serviceIntent).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_REPLY)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AssistantOverlayService.EXTRA_REPLY).build()), intent,
                Bundle().apply { putCharSequence(AssistantOverlayService.EXTRA_REPLY, text) })
            context.startService(intent)
        }
        fun submitAndWaitForSaveFailure(text: String) {
            val label = context.getString(ru.outsideproarts.oassist.feature.assistant.R.string.assistant_history_save_failed)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeAndWaitForEvent(
                { submitService(text) },
                { event -> event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString().contains(label) } },
                20_000,
            ).recycle()
        }
        fun visibleNotice(): Boolean {
            val label = context.getString(ru.outsideproarts.oassist.core.ui.R.string.thinking_context_reduced)
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && node.text?.toString() == label) return true
                return (0 until node.childCount).any { index -> node.getChild(index)?.let(::contains) == true }
            }
            return InstrumentationRegistry.getInstrumentation().uiAutomation.windows.any { window ->
                window.root?.let(::contains) == true
            }
        }
        try {
            AgentFactory.register(factory)
            ChatHandle.register(repository)
            NetworkHandle.register(object : NetworkHandle { override val isOnline = MutableStateFlow(true) })
            VoiceHandle.register(object : VoiceHandle by previousVoice {
                override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): ru.outsideproarts.oassist.core.speech.CaptureError? {
                    sttStarted.complete(Unit)
                    return null
                }
                override fun cancelStt() = Unit
                override fun stopStt() = Unit
            })
            compose.setContent {
                MaterialTheme {
                    visible.value?.let { vm ->
                        androidx.compose.runtime.key(vm) {
                            ChatScreen(viewModel = vm, soundEnabled = false, sendMessage = { requestedMode, text, history, _, callback ->
                                val agent = factory.agentFor(profile, requestedMode)
                                AgentSession(agent.stream("alias", "system", history, text, onTurnMessages = callback), agent)
                            })
                        }
                    }
                }
            }
            val id = if (mode == AssistMode.ASSISTANT) ChatRepository.ASSISTANT_CHAT_ID else repository.createChat(mode, "Terminal")
            val saveFailureCase = ending == "saveRetry" || ending == "saveReplace"
            if (saveFailureCase) database.openHelper.writableDatabase.execSQL("CREATE TRIGGER terminal_fail BEFORE INSERT ON turns BEGIN SELECT RAISE(ABORT, 'terminal service save fixture'); END")
            var shareVm: ShareSessionViewModel? = null
            when (mode) {
                AssistMode.CHAT -> { compose.runOnIdle { visible.value = chat(id) }; sendChat("act") }
                AssistMode.SHARE -> {
                    if (ending == "drop") {
                        shareActivity = ActivityScenario.launch(ShareActivity::class.java)
                        shareActivity!!.onActivity { activity -> shareVm = ViewModelProvider(activity)[ShareSessionViewModel::class.java] }
                        compose.runOnIdle {
                            shareVm!!.openChat(ChatSummary(id, "Terminal", ChatKind.REGULAR, AssistMode.SHARE, 0, 0))
                        }
                        compose.waitUntil(10_000) { shareVm!!.canSend }
                    } else shareVm = share(id)
                    askShare(shareVm!!, "act")
                }
                AssistMode.ASSISTANT -> {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
                    if (ending == "drop") {
                        assertTrue("Owned test device must grant the overlay app-op", android.provider.Settings.canDrawOverlays(context))
                        context.startService(serviceIntent)
                        withTimeout(10_000) { sttStarted.await() }
                    }
                    if (saveFailureCase) submitAndWaitForSaveFailure("act") else submitService("act")
                }
            }
            try {
                withTimeout(20_000) { secondPost.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Second POST missing: mode=$mode ending=$ending posts=${requests.size} bodyTypes=$requestTypes effects=${effects.get()}", timeout)
            }
            if (ending == "cancel" || ending == "destroy") {
                when (mode) {
                    AssistMode.CHAT -> compose.runOnIdle {
                        if (ending == "destroy") stores.getValue(visible.value!!).clear() else visible.value!!.streamJob!!.cancel()
                    }
                    AssistMode.SHARE -> compose.runOnIdle { shareVm!!.leaveConversation() }
                    AssistMode.ASSISTANT -> if (ending == "destroy") context.stopService(serviceIntent) else
                        context.startService(Intent(serviceIntent).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_MIC))
                }
            }
            if (ending == "saveRetry") {
                assertTrue(AssistSessionStore.current()!!.history.any { it.role == ChatRole.TOOL })
                submitAndWaitForSaveFailure("retry failed save")
                assertEquals(2, requests.size)
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER terminal_fail")
                submitService("retry saved")
                compose.waitUntil(20_000) { requests.size == 3 }
                compose.waitUntil(20_000) { runBlocking { repository.loadTurns(id).size == 2 } }
            }
            if (ending == "saveReplace") {
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER terminal_fail")
                submitService("cancelled retry")
                assertTrue(preflightEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                context.startService(Intent(serviceIntent).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_MIC))
                submitService("replacement")
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                releasePreflight.countDown()
                withTimeout(20_000) { replacementPost.await() }
                submitService("must be ignored while busy")
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(3, requests.size)
                releaseReplacement.complete(Unit)
                compose.waitUntil(20_000) { runBlocking { repository.loadTurns(id).size == 2 } }
                assertEquals(listOf("act", "replacement"), repository.loadTurns(id).map { it.userText })
            }
            compose.waitUntil(20_000) { runBlocking { repository.loadTurns(id).size == if (saveFailureCase) 2 else 1 } }
            val saved = repository.loadTurns(id).first()
            assertEquals(1, effects.get())
            assertEquals(firstBlocks, Json.parseToJsonElement(saved.turn.first().reasoning!!.anthropicReplay!!.blocksJson))
            assertEquals(listOf("external", "read-skill"), saved.turn.filter { it.role == ChatRole.TOOL }.map { it.toolCallId })
            assertTrue(saved.turn.any { it.content == "external action committed" })
            assertTrue(requests[1]["system"].toString().contains("Owner procedure"))
            assertTrue(requests[1]["thinking"].toString().contains("drop_block"))
            assertFalse(saved.turn.toString().contains("THINKING_CONTEXT_REDUCED"))
            if (ending == "drop" && mode == AssistMode.CHAT) {
                compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.core.ui.R.string.thinking_context_reduced)).assertIsDisplayed()
            }
            if (ending == "drop" && mode == AssistMode.SHARE) {
                assertNotNull(shareVm!!.providerNotice)
                compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.core.ui.R.string.thinking_context_reduced)).assertIsDisplayed()
            }
            if (ending == "drop" && mode == AssistMode.ASSISTANT) compose.waitUntil(10_000) { visibleNotice() }
            var expectedPosts = if (saveFailureCase) 3 else 2
            var expectedTurns = if (saveFailureCase) 2 else 1
            if (saveFailureCase) {
                assertEquals(firstBlocks, requests[2]["messages"]!!.jsonArray.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject["content"])
                assertTrue(requests[2].toString().contains("external action committed"))
            }
            if (mode == AssistMode.ASSISTANT && ending != "destroy" && !saveFailureCase) {
                submitService("continue same service")
                compose.waitUntil(20_000) { requests.size == 3 }
                compose.waitUntil(20_000) { runBlocking { repository.loadTurns(id).size == 2 } }
                assertEquals(1, effects.get())
                assertTrue(requests.last().toString().contains("external action committed"))
                val liveReplay = requests.last()["messages"]!!.jsonArray.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject["content"]
                assertEquals(firstBlocks, liveReplay)
                expectedPosts = 3
                expectedTurns = 2
            }
            context.stopService(serviceIntent)
            shareActivity?.close()
            shareActivity = null
            compose.runOnIdle { visible.value = null; chatOwners.forEach { it.scope.cancel() }; shareOwners.forEach { it.scope.cancel() } }
            compose.waitForIdle()
            database.close()
            database = open()
            repository = ChatRepository(database, ChatMediaStore(mediaRoot))
            ChatHandle.register(repository)
            val reopened = repository.loadTurns(id)
            assertEquals(saved, reopened.first())
            assertEquals(expectedTurns, reopened.size)
            if (mode == AssistMode.SHARE) {
                val next = share(id)
                askShare(next, "continue")
            } else {
                compose.runOnIdle { visible.value = chat(id, reopened) }
                sendChat("continue")
            }
            compose.waitUntil(20_000) { requests.size >= expectedPosts + 1 }
            assertEquals(expectedPosts + 1, requests.size)
            assertEquals(1, effects.get())
            val replayed = requests.last()["messages"]!!.jsonArray.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject["content"]
            assertEquals(firstBlocks, replayed)
            assertTrue(requests.last().toString().contains("external action committed"))
            assertTrue(requests.last().toString().contains("Owner procedure"))
            compose.waitUntil(20_000) { runBlocking { repository.loadTurns(id).size == expectedTurns + 1 } }
        } finally {
            releasePreflight.countDown()
            releaseReplacement.complete(Unit)
            context.stopService(serviceIntent)
            shareActivity?.close()
            compose.runOnIdle { visible.value = null; chatOwners.forEach { it.scope.cancel() }; shareOwners.forEach { it.scope.cancel() } }
            compose.waitForIdle()
            AgentFactory.register(previousFactory)
            ChatHandle.register(previousRepository)
            NetworkHandle.register(previousNetwork)
            VoiceHandle.register(previousVoice)
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousAccessibilityFlags }
            http.close()
            database.close()
            context.deleteDatabase(name)
            mediaRoot.deleteRecursively()
            AssistSessionStore.clear()
        }
    }

    companion object {
        private class SseEngine(config: MockEngineConfig) : MockEngine(config) {
            override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> = super.supportedCapabilities + SSECapability
            @OptIn(InternalAPI::class)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                val response = super.execute(data)
                val body = response.body as? ByteReadChannel ?: return response
                val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(
                    data, response.statusCode, response.headers, body, data.body, response.callContext,
                ) ?: return response
                return HttpResponseData(response.statusCode, response.requestTime, response.headers, response.version, adapted, response.callContext)
            }
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}") fun cases() = AssistMode.entries.flatMap { mode ->
            (listOf("provider", "prefix", "cancel", "drop") +
                if (mode == AssistMode.ASSISTANT) listOf("destroy", "saveRetry", "saveReplace") else if (mode == AssistMode.CHAT) listOf("destroy") else emptyList()
            ).map { arrayOf<Any>(mode, it) }
        }
        private fun sse(blocks: JsonArray, notice: Boolean = false): String = buildString {
            fun event(type: String, data: JsonObject) { append("event: $type\ndata: $data\n\n") }
            event("message_start", buildJsonObject { put("type", "message_start") })
            blocks.forEachIndexed { index, value ->
                val block = value.jsonObject
                event("content_block_start", buildJsonObject { put("index", index); put("content_block", block) })
                event("content_block_stop", buildJsonObject { put("index", index) })
            }
            if (notice) append("event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"},\"input_transformations\":[{\"type\":\"thinking_dropped\",\"path\":\"messages.1.content.0\",\"reason\":\"prefix_binding_mismatch\"}]}\n\n")
            event("message_stop", buildJsonObject { put("type", "message_stop") })
        }
        private object EmptySecrets : SecretStore {
            override suspend fun put(alias: String, plaintext: CharArray) = Unit
            override suspend fun get(alias: String): CharArray? = null
            override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) = Unit
            override suspend fun getPair(firstAlias: String?, secondAlias: String?) = null to null
            override suspend fun contains(alias: String) = false
            override suspend fun delete(alias: String) = Unit
            override suspend fun clearAll() = Unit
            override suspend fun aliases() = emptyList<String>()
        }
    }
}
