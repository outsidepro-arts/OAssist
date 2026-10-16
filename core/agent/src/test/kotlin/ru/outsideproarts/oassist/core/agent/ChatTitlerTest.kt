package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.UsageTotalsState
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class ChatTitlerTest {

    @Test
    fun placeholder_takesFirstLineAndCaps() {
        assertEquals("Привет, как дела?", ChatTitler.placeholder("Привет, как дела?\nВторая строка"))
        assertEquals(
            "aa bb cc dd ee ff gg hh",
            ChatTitler.placeholder("aa bb cc dd ee ff gg hh ii jj"),
        )
        assertEquals("", ChatTitler.placeholder("   \n  "))
    }

    @Test
    fun placeholder_capsByLength() {
        val title = ChatTitler.placeholder("а".repeat(100))
        assertEquals(ChatTitler.MAX_TITLE_LENGTH, title.length)
        assertEquals('…', title.last())
    }

    @Test
    fun parseTitle_stripsQuotesAndFormatting() {
        assertEquals("Рецепты на зиму", ChatTitler.parseTitle("«Рецепты на зиму»"))
        assertEquals("Рецепты", ChatTitler.parseTitle("**Рецепты**"))
        assertEquals("Рецепты", ChatTitler.parseTitle("\"Рецепты\"\nЕщё пояснение"))
        assertNull(ChatTitler.parseTitle("   \n "))
    }

    @Test
    fun generateTitle_callsModelAndParses() = runTest {
        val factory = fakeFactory(response = "«Рецепты на зиму»")
        val title = ChatTitler.generateTitle(AssistMode.CHAT, "как варить варенье из клубники", factory)
        assertEquals("Рецепты на зиму", title)
    }

    @Test
    fun generateTitle_withoutModel_returnsNull() = runTest {
        assertNull(ChatTitler.generateTitle(AssistMode.CHAT, "привет", fakeFactory(response = "x", withModel = false)))
    }

    @Test
    fun generateTitle_blankMessage_returnsNull() = runTest {
        assertNull(ChatTitler.generateTitle(AssistMode.CHAT, "   ", fakeFactory(response = "x")))
    }

    private fun fakeFactory(response: String, withModel: Boolean = true): AgentFactory {
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
            ): Flow<ChatChunk> = flowOf(ChatChunk.Content(response), ChatChunk.Finish("stop"))
        }
        return object : AgentFactory {
            override suspend fun modeConfig(mode: AssistMode): ModeConfig =
                ModeConfig.default(mode).copy(modelId = if (withModel) "m1" else null)

            override suspend fun providerForMode(mode: AssistMode): ProviderProfile? =
                if (withModel) {
                    ProviderProfile(id = "p", name = "p", kind = ProviderKind.OPENAI_COMPAT, baseUrl = "https://x")
                } else {
                    null
                }

            override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode): AgentLoop =
                AgentLoop(client, toolRegistry = null)

            override fun clearSessionApprovals(mode: AssistMode) = Unit

            override fun usageTotals(): StateFlow<UsageTotalsState> = MutableStateFlow(UsageTotalsState())

            override suspend fun systemPromptFor(
                mode: AssistMode,
                screenContext: String?,
                sharedContent: String?,
            ): String = ""
        }
    }
}
