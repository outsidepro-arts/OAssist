package ru.outsideproarts.oassist.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.StoredTurnEntry
import ru.outsideproarts.oassist.core.model.WorkEvent
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.ChatSessionViewModel
import ru.outsideproarts.oassist.feature.chat.R as ChatR

/**
 * Перечитывание открытого диалога при возврате экрана чата в активное состояние:
 * фоновые записи (Share, снимок ассистента) появляются на экране без переоткрытия,
 * идущий стрим не затирается, удалённый в фоне диалог сбрасывается.
 * Возврат в активное состояние моделируется реальным лайфциклом хост-активити.
 */
@RunWith(AndroidJUnit4::class)
class ChatResumeRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun backgroundWriteAppearsAfterReturningToForeground() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Диалог")
            fixture.repository.appendTurn(id, StoredTurn(userText = "первый", answer = "ответ"))
            withScreen(fixture, id) { vm ->
                // Экран при появлении уже перечитал диалог.
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                // Запись в тот же чат из другого режима (как ShareActivity).
                runBlocking {
                    fixture.repository.appendTurn(id, StoredTurn(userText = "файл из Share", answer = "принято"))
                }
                returnToForeground()
                compose.waitUntil(10_000) {
                    vm.turnStore.size == 2 && vm.turnStore.last().turn.userText == "файл из Share"
                }
                compose.onNodeWithText("файл из Share", substring = true).assertExists()
                compose.onNodeWithText("принято", substring = true).assertExists()
            }
        }
    }

    @Test fun assistantSnapshotAppearsAfterReturningToForeground() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            fixture.repository.replaceAssistantTurns {
                listOf(StoredTurnEntry("a1", StoredTurn(userText = "привет", answer = "ответ ассистента")))
            }
            withScreen(fixture, ChatRepository.ASSISTANT_CHAT_ID) { vm ->
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                // Оверлей ассистента перезаписал снимок диалога (тот же id хода сохраняется).
                runBlocking {
                    fixture.repository.replaceAssistantTurns {
                        listOf(
                            StoredTurnEntry("a1", StoredTurn(userText = "привет", answer = "ответ ассистента")),
                            StoredTurnEntry("a2", StoredTurn(userText = "продолжение", answer = "сделал")),
                        )
                    }
                }
                returnToForeground()
                compose.waitUntil(10_000) {
                    vm.turnStore.size == 2 && vm.turnStore.last().turn.userText == "продолжение"
                }
                // Тип перечитывается из БД: диалог ассистента открывался как обычный.
                compose.runOnIdle {
                    assertTrue(vm.chatKind == ChatKind.ASSISTANT)
                }
                compose.onNodeWithText("сделал", substring = true).assertExists()
            }
        }
    }

    @Test fun activeStreamSurvivesReturningToForeground() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Диалог")
            fixture.repository.appendTurn(id, StoredTurn(userText = "первый", answer = "ответ"))
            val client = object : ModelClient {
                override suspend fun fetchModels(): List<ModelInfo> = emptyList()
                override fun chatCompletionStream(
                    modelId: String,
                    messages: List<ChatMessage>,
                    tools: List<ToolSchemaDto>,
                    reasoningEnabled: Boolean,
                ) = flow {
                    // Мысли идут в UI live (контент ответа AgentLoop буферизует до
                    // конца раунда — в пузыре его во время стрима по дизайну нет).
                    emit(ChatChunk.Reasoning("идёт размышление"))
                    awaitCancellation()
                }
            }
            withScreen(fixture, id, client = client) { vm ->
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                send("новый вопрос")
                compose.waitUntil(10_000) { vm.isStreaming }
                compose.waitUntil(10_000) { hasLiveThought(vm) }
                // Пока идёт стрим, фоновая запись не должна затирать идущий ход.
                runBlocking {
                    fixture.repository.appendTurn(id, StoredTurn(userText = "из Share", answer = "вне очереди"))
                }
                returnToForeground()
                // Если перечитывание при стриме ошибочно запустится, ожидание
                // дождётся его применения — и проверки ниже уронят тест.
                compose.waitUntil(10_000) { !vm.isLoadingHistory }
                compose.runOnIdle {
                    assertTrue("стрим должен продолжаться", vm.isStreaming)
                    assertTrue("фоновая запись не перечитывается во время стрима", vm.turnStore.size == 1)
                    assertTrue("полотно идущего хода не затёрто", hasLiveThought(vm))
                }
            }
        }
    }

    @Test fun chatDeletedInBackgroundResetsScreen() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Диалог")
            fixture.repository.appendTurn(id, StoredTurn(userText = "первый", answer = "ответ"))
            withScreen(fixture, id) { vm ->
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                // Диалог удалили из другого режима (как ShareActivity).
                runBlocking { fixture.repository.deleteChat(id) }
                returnToForeground()
                compose.waitUntil(10_000) { vm.chatId == null && vm.messages.isEmpty() }
            }
        }
    }

    @Test fun renamedInBackgroundChatUpdatesTitle() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Диалог")
            fixture.repository.appendTurn(id, StoredTurn(userText = "первый", answer = "ответ"))
            withScreen(fixture, id) { vm ->
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                runBlocking { fixture.repository.renameChat(id, "Новое имя") }
                returnToForeground()
                compose.waitUntil(10_000) { vm.chatTitle == "Новое имя" }
            }
        }
    }

    @Test fun turnWithFailedSaveIsNotClobberedByRefresh() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Диалог")
            fixture.repository.appendTurn(id, StoredTurn(userText = "первый", answer = "ответ"))
            withScreen(fixture, id) { vm ->
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                // Запись следующего хода падает: ход живёт только в памяти.
                fixture.database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_insert BEFORE INSERT ON turns BEGIN SELECT RAISE(ABORT, 'resume refresh save fixture'); END",
                )
                send("второй")
                compose.waitUntil(10_000) { vm.turnStore.size == 2 && !vm.isStreaming }
                returnToForeground()
                compose.waitUntil(10_000) { !vm.isLoadingHistory }
                compose.runOnIdle {
                    assertTrue("незаписанный ход не должен затираться", vm.turnStore.size == 2)
                    assertTrue("незаписанный ход должен остаться в ленте", vm.messages.any { it.answer == "второй" })
                }
            }
        }
    }

    @Test fun twoQuickReturnsStillShowTheWrite() = runBlocking {
        ChatPersistenceFixture().use { fixture ->
            val id = fixture.repository.createChat(AssistMode.CHAT, "Диалог")
            fixture.repository.appendTurn(id, StoredTurn(userText = "первый", answer = "ответ"))
            withScreen(fixture, id) { vm ->
                compose.waitUntil(10_000) { vm.turnStore.size == 1 }
                runBlocking {
                    fixture.repository.appendTurn(id, StoredTurn(userText = "из Share", answer = "принято"))
                }
                returnToForeground()
                returnToForeground()
                compose.waitUntil(10_000) {
                    vm.turnStore.size == 2 && vm.turnStore.last().turn.userText == "из Share"
                }
            }
        }
    }

    /** Живой мыслительный блок идущего хода в ленте. */
    private fun hasLiveThought(vm: ChatSessionViewModel): Boolean =
        vm.messages.any { message -> message.work.any { it is WorkEvent.Thought && it.text == "идёт размышление" } }

    /** Возврат приложения из фона: пауза/стоп и обратно в RESUMED. */
    private fun returnToForeground() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun send(text: String) {
        compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint))
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
        compose.waitForIdle()
    }

    /** Экран чата с фикстурой БД; хост-активити даёт реальный лайфцикл. */
    private fun withScreen(
        fixture: ChatPersistenceFixture,
        id: String?,
        kind: ChatKind = ChatKind.REGULAR,
        client: ModelClient = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
                reasoningEnabled: Boolean,
            ) = flow {
                emit(ChatChunk.Content("ответ"))
                emit(ChatChunk.Finish("stop"))
            }
        },
        action: (ChatSessionViewModel) -> Unit,
    ) {
        val previousRepository = checkNotNull(ChatHandle.get())
        val previousNetwork = checkNotNull(NetworkHandle.get())
        val vm = ChatSessionViewModel().apply {
            chatId = id
            chatTitle = "Диалог"
            titleLocked = true
            chatKind = kind
        }
        val visible = mutableStateOf(true)
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
                                val agent = AgentLoop(client, diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics)
                                AgentSession(agent.stream("fixture", null, history, text, onTurnMessages = callback, onPinChange = pinCallback), agent)
                            },
                        )
                    }
                }
            }
            compose.waitForIdle()
            // Экран перечитывает открытый диалог при появлении — дождаться готовности.
            compose.waitUntil(10_000) { !vm.isLoadingHistory }
            action(vm)
        } finally {
            compose.runOnIdle { vm.streamJob?.cancel() }
            compose.waitUntil(10_000) { vm.streamJob == null }
            compose.runOnIdle { visible.value = false; vm.scope.cancel() }
            compose.waitForIdle()
            ChatHandle.register(previousRepository)
            NetworkHandle.register(previousNetwork)
        }
    }
}
