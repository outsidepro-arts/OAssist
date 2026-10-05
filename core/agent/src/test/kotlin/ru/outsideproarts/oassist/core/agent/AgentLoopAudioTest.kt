package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.AudioPart
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Голосовой ход доходит до модели с аудио-приложением и текстом-заглушкой.
 */
class AgentLoopAudioTest {

    private class FakeClient : ModelClient {
        val seen = mutableListOf<List<ChatMessage>>()
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
        ): Flow<ChatChunk> {
            seen.add(messages)
            return flowOf(ChatChunk.Content("понял"), ChatChunk.Finish("stop"))
        }
    }

    @Test
    fun voiceMessage_carriesAudioToModel() = runTest {
        val client = FakeClient()
        val agent = AgentLoop(client, toolRegistry = null)

        agent.stream("m", null, emptyList(), "", AudioPart("QUJDRA==", durationSec = 5)).toList()

        val userTurn = client.seen.single().single { it.role == ChatRole.USER }
        assertEquals(AudioPart("QUJDRA==", durationSec = 5), userTurn.audio)
    }

    @Test
    fun textMessage_hasNoAudio() = runTest {
        val client = FakeClient()
        val agent = AgentLoop(client, toolRegistry = null)

        agent.stream("m", null, emptyList(), "привет").toList()

        val userTurn = client.seen.single().single { it.role == ChatRole.USER }
        assertEquals(null, userTurn.audio)
        assertEquals("привет", userTurn.content)
    }
}
