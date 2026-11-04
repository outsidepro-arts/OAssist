package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopImagesTest {

    private class ScriptedClient(val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        val seenMessages = mutableListOf<List<ChatMessage>>()
        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            seenMessages.add(messages)
            return flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
        }
    }

    private class ShotTool : Tool {
        override val groupKey = ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE
        override val schema = ToolSchema(
            name = "shot",
            description = "shot",
            parameters = buildJsonObject { put("type", "object") },
        )
        override suspend fun execute(arguments: JsonObject): ToolResult =
            ToolResult(true, "ok", images = listOf(ImagePart(imageBase64 = "QUJD")))
    }

    private fun scripted(): ScriptedClient = ScriptedClient(
        ArrayDeque(
            listOf(
                listOf(
                    ChatChunk.ToolCall(id = "c1", name = "shot", argumentsJson = "{}"),
                    ChatChunk.Finish("tool_calls"),
                ),
                listOf(ChatChunk.Content("done"), ChatChunk.Finish("stop")),
            ),
        ),
    )

    @Test
    fun toolImages_reachToolMessage() = runTest {
        val client = scripted()
        val agent = AgentLoop(client, ToolRegistry(listOf(ShotTool())))
        agent.stream("m", null, emptyList(), "q").toList()

        val secondRound = client.seenMessages[1]
        val toolMsg = secondRound.last { it.role == ChatRole.TOOL }
        assertEquals(listOf("QUJD"), toolMsg.images.map { it.imageBase64 })
    }

    @Test
    fun visionDisabled_stripsToolImagesWithNote() = runTest {
        val client = scripted()
        val agent = AgentLoop(client, ToolRegistry(listOf(ShotTool())), visionEnabled = false)
        agent.stream("m", null, emptyList(), "q").toList()

        val toolMsg = client.seenMessages[1].last { it.role == ChatRole.TOOL }
        assertTrue(toolMsg.images.isEmpty())
        assertTrue(toolMsg.content.contains(IMAGE_DISABLED_NOTE.trim()))
    }

    @Test
    fun userImages_reachUserMessage() = runTest {
        val client = scripted()
        val agent = AgentLoop(client, ToolRegistry(listOf(ShotTool())))
        agent.stream("m", null, emptyList(), "q", images = listOf(ImagePart(imageBase64 = "UUU="))).toList()

        val userMsg = client.seenMessages[0].last { it.role == ChatRole.USER }
        assertEquals(listOf("UUU="), userMsg.images.map { it.imageBase64 })
    }

    @Test
    fun visionDisabled_stripsUserImagesWithNote() = runTest {
        val client = scripted()
        val agent = AgentLoop(client, ToolRegistry(listOf(ShotTool())), visionEnabled = false)
        agent.stream("m", null, emptyList(), "q", images = listOf(ImagePart(imageBase64 = "UUU="))).toList()

        val userMsg = client.seenMessages[0].last { it.role == ChatRole.USER }
        assertTrue(userMsg.images.isEmpty())
        assertTrue(userMsg.content.contains(IMAGE_DISABLED_NOTE.trim()))
    }

    @Test
    fun imageBudgetNotesRemainInsideStructuredHistory() = runTest {
        val client = ScriptedClient(ArrayDeque(listOf(listOf(ChatChunk.Finish("stop")))))
        val history = (0..MAX_IMAGE_MESSAGES).map {
            ChatMessage(ChatRole.USER, "old $it", images = listOf(ImagePart(imageBase64 = "QUJD")))
        }
        AgentLoop(client).stream("m", null, history, "question").toList()
        val oldest = client.seenMessages.single().first { it.role == ChatRole.USER }
        val envelope = Json.parseToJsonElement(oldest.content).jsonObject
        assertTrue(envelope["content"]!!.jsonPrimitive.content.contains(IMAGE_REMOVED_NOTE))
        assertTrue(oldest.images.isEmpty())
        assertTrue(history.first().images.isNotEmpty())
    }

    @Test
    fun disabledVisionFiltersHistoryAndPreservesItForVisionModel() = runTest {
        val image = ImagePart(imageBase64 = "QUJD")
        val history = listOf(
            ChatMessage(ChatRole.USER, "old user", images = listOf(image)),
            ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(
                ru.outsideproarts.oassist.core.model.ToolCallRef("call", "shot", "{}", "signature", "wire-id"),
            ), reasoning = ru.outsideproarts.oassist.core.model.ReasoningBlock(text = "thinking")),
            ChatMessage(ChatRole.TOOL, "old tool", toolCallId = "call", images = listOf(image)),
        )
        val original = history.toList()
        val disabled = ScriptedClient(ArrayDeque())
        AgentLoop(disabled, visionEnabled = false).stream("m", null, history, "question").toList()
        val sent = disabled.seenMessages.single()
        assertTrue(sent.all { it.images.isEmpty() })
        val oldUser = sent.first { it.role == ChatRole.USER }
        val oldTool = sent.first { it.role == ChatRole.TOOL }
        assertTrue(oldUser.content.contains(IMAGE_DISABLED_NOTE.trim()))
        assertTrue(oldTool.content.contains(IMAGE_DISABLED_NOTE.trim()))
        assertEquals("call", oldTool.toolCallId)
        assertEquals(history[1].toolCalls, sent.first { it.role == ChatRole.ASSISTANT }.toolCalls)
        assertEquals(history[1].reasoning, sent.first { it.role == ChatRole.ASSISTANT }.reasoning)
        assertEquals(original, history)
        assertEquals(listOf(image), history[0].images)
        assertEquals(listOf(image), history[2].images)

        val enabled = ScriptedClient(ArrayDeque())
        AgentLoop(enabled, visionEnabled = true).stream("m", null, history, "question").toList()
        val restored = enabled.seenMessages.single()
        assertEquals(listOf(image), restored.first { it.role == ChatRole.USER }.images)
        assertEquals(listOf(image), restored.first { it.role == ChatRole.TOOL }.images)
    }

    @Test
    fun disabledVisionKeepsNewToolImagesInTurnTranscript() = runTest {
        val client = scripted()
        var turn = emptyList<ChatMessage>()
        AgentLoop(client, ToolRegistry(listOf(ShotTool())), visionEnabled = false)
            .stream("m", null, emptyList(), "q", onTurnMessages = { turn = it }).toList()
        assertEquals(listOf("QUJD"), turn.first { it.role == ChatRole.TOOL }.images.map { it.imageBase64 })
        assertTrue(client.seenMessages[1].first { it.role == ChatRole.TOOL }.images.isEmpty())
    }
}
