package ru.outsideproarts.oassist.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.feature.chat.ChatScreen
import ru.outsideproarts.oassist.feature.chat.ChatSessionViewModel
import ru.outsideproarts.oassist.feature.chat.R as ChatR

@RunWith(AndroidJUnit4::class)
class ChatOfflineRetryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingTurnSendsOnceAfterReturningOnline() = withScreen { online, visible, sends ->
        send("first")
        assertEquals(0, sends.get())
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { online.value = true }
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(5_000) { sends.get() == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(1, sends.get())
    }

    @Test fun cancelledPendingTurnDoesNotResumeAndNextTurnCanRetry() = withScreen { online, visible, sends ->
        send("cancelled")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_stop)).performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { online.value = true; visible.value = true }
        compose.waitForIdle()
        assertEquals(0, sends.get())
        compose.runOnIdle { online.value = false }
        send("next")
        compose.runOnIdle { online.value = true }
        compose.waitUntil(5_000) { sends.get() == 1 }
    }

    private fun send(text: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(ChatR.string.chat_input_hint)).performTextInput(text)
        compose.onNodeWithContentDescription(context.getString(ChatR.string.chat_send)).performClick()
    }

    private fun withScreen(action: (MutableStateFlow<Boolean>, androidx.compose.runtime.MutableState<Boolean>, AtomicInteger) -> Unit) {
        val previousNetwork = checkNotNull(NetworkHandle.get())
        val previousRepository = checkNotNull(ChatHandle.get())
        val previousFactory = AgentFactory.get()
        val fixture = ChatPersistenceFixture()
        val online = MutableStateFlow(false)
        val visible = mutableStateOf(true)
        val sends = AtomicInteger()
        val vm = ChatSessionViewModel().apply { titleLocked = true }
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean) = flow {
                emit(ChatChunk.Content("answer"))
                emit(ChatChunk.Finish("stop"))
            }
        }
        NetworkHandle.register(object : NetworkHandle { override val isOnline = online })
        ChatHandle.register(fixture.repository)
        AgentFactory.register(object : AgentFactory by previousFactory {
            override suspend fun modeConfig(mode: AssistMode) = ModeConfig(mode, autoSaveChats = false)
            override suspend fun providerForMode(mode: AssistMode) = null
        })
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible.value) ChatScreen(viewModel = vm, soundEnabled = false, sendMessage = { _, text, history, _, callback, pinCallback ->
                        sends.incrementAndGet()
                        val agent = AgentLoop(client, diagnostics = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics)
                        AgentSession(agent.stream("fixture", null, history, text, onTurnMessages = callback, onPinChange = pinCallback), agent)
                    })
                }
            }
            compose.waitForIdle()
            action(online, visible, sends)
        } finally {
            compose.runOnIdle { visible.value = false; vm.scope.cancel() }
            compose.waitForIdle()
            NetworkHandle.register(previousNetwork)
            ChatHandle.register(previousRepository)
            AgentFactory.register(previousFactory)
            fixture.close()
        }
    }
}
