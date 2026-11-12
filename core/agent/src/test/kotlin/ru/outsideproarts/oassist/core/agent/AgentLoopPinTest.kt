package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.MessagePin
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.PinChange
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AgentLoopPinTest {

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

    private fun pinClient(pinArgs: String) = ScriptedClient(
        ArrayDeque(
            listOf(
                listOf(
                    ChatChunk.ToolCall(id = "c1", name = "message_pin", argumentsJson = pinArgs),
                    ChatChunk.Finish("tool_calls"),
                ),
                listOf(ChatChunk.Content("done"), ChatChunk.Finish("stop")),
            ),
        ),
    )

    private fun agent(client: ModelClient, visionEnabled: Boolean = true) = AgentLoop(
        client,
        ToolRegistry(listOf(MessagePinTool(), MessageUnpinTool())),
        approvalPolicy = InMemoryApprovalPolicy().apply {
            setRemembered(ToolGroups.key(ToolCategory.APP_DATA, ToolAccess.WRITE), ApprovalState.ALLOW)
        },
        visionEnabled = visionEnabled,
    )

    private fun envelopeOf(message: ChatMessage) = Json.parseToJsonElement(message.content).jsonObject

    @Test
    fun pinHistoryMessage_movesReferenceImageToCurrentRequest() = runTest {
        val image = ImagePart(imageBase64 = "QUJD")
        // Больше сообщений с образами, чем бюджет: старые незакреплённые усекаются.
        val history = (0..MAX_IMAGE_MESSAGES + 1).map { index ->
            ChatMessage(ChatRole.USER, "old $index", images = listOf(image), id = "t$index:0")
        }
        val changes = mutableListOf<PinChange>()
        val client = pinClient("""{"message_id":"t0:0","note":"референс"}""")
        agent(client).stream(
            "m", null, history, "вопрос",
            onPinChange = { changes += it },
        ).toList()

        val secondRound = client.seenMessages[1]
        val current = secondRound.last {
            it.role == ChatRole.USER && it.content.contains("current_user_input")
        }
        // Референс доставлен в текущем сообщении — свежая позиция в поле зрения модели.
        assertEquals(listOf(image), current.images)
        // Конверт объясняет модели: первое изображение — закреплённый референс
        // из t0:0 с его заметкой, остальные — вложения текущего запроса.
        val ref = envelopeOf(current)["pinnedImages"]!!.jsonArray.single().jsonObject
        assertEquals("t0:0", ref["source"]!!.jsonPrimitive.content)
        assertEquals("1-1", ref["images"]!!.jsonPrimitive.content)
        assertEquals("референс", ref["note"]!!.jsonPrimitive.content)
        val hint = envelopeOf(current)["pinnedHint"]!!.jsonPrimitive.content
        assertTrue(hint.contains("Закреплённые референсы"))
        assertTrue(hint.contains("pinnedImages"))

        // Историческое сообщение остаётся на месте с метками закрепления и заметкой.
        val byId = secondRound.filter { it.role == ChatRole.USER }
            .associateBy { envelopeOf(it)["id"]?.jsonPrimitive?.content }
        val source = byId.getValue("t0:0")
        assertTrue(source.images.isEmpty())
        assertTrue(source.content.contains(PINNED_IMAGE_MOVED_NOTE))
        val sourceEnvelope = envelopeOf(source)
        assertEquals("true", sourceEnvelope["pinned"]!!.jsonPrimitive.content)
        assertEquals("референс", sourceEnvelope["pinNote"]!!.jsonPrimitive.content)

        // Бюджет обычной истории переезд не трогает: t2 в лимите, t1 усечён.
        assertEquals(listOf(image), byId.getValue("t2:0").images)
        assertTrue(byId.getValue("t1:0").images.isEmpty())
        assertEquals(listOf(PinChange("t0:0", pinned = true, note = "референс")), changes)
    }

    @Test
    fun pinnedImagesWithoutVision_areStrippedNotHoisted() = runTest {
        val image = ImagePart(imageBase64 = "QUJD")
        val history = listOf(
            ChatMessage(ChatRole.USER, "референс", images = listOf(image), id = "t0:0", pin = MessagePin("эталон")),
            ChatMessage(ChatRole.USER, "незакреплённое", images = listOf(image), id = "t1:0"),
        )
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("done"), ChatChunk.Finish("stop")))),
        )

        agent(client, visionEnabled = false).stream("m", null, history, "вопрос").toList()

        val request = client.seenMessages.single()
        // У модели нет vision: образы вырезаются с пометкой — переезжать нечему.
        assertTrue(request.all { it.images.isEmpty() })
        assertFalse(request.any { it.content.contains(PINNED_IMAGE_MOVED_NOTE) })
        val byId = request.filter { it.role == ChatRole.USER }
            .associateBy { envelopeOf(it)["id"]?.jsonPrimitive?.content }
        assertTrue(byId.getValue("t0:0").content.contains(IMAGE_DISABLED_NOTE.trim()))
        assertEquals("true", envelopeOf(byId.getValue("t0:0"))["pinned"]!!.jsonPrimitive.content)
        // Конверт текущего сообщения не ссылается на несуществующие образы.
        val current = request.last {
            it.role == ChatRole.USER && it.content.contains("current_user_input")
        }
        assertFalse(envelopeOf(current).containsKey("pinnedImages"))
        assertFalse(envelopeOf(current).containsKey("pinnedHint"))
    }

    @Test
    fun severalPinnedSources_getCumulativeImageRanges() = runTest {
        val image = ImagePart(imageBase64 = "QUJD")
        val history = listOf(
            ChatMessage(ChatRole.USER, "два", images = listOf(image, image), id = "t0:0", pin = MessagePin("пара")),
            ChatMessage(ChatRole.USER, "один", images = listOf(image), id = "t1:0", pin = MessagePin()),
            ChatMessage(ChatRole.USER, "незакреплённое", images = listOf(image), id = "t2:0"),
        )
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("done"), ChatChunk.Finish("stop")))),
        )

        agent(client).stream("m", null, history, "вопрос").toList()

        val current = client.seenMessages.single().last {
            it.role == ChatRole.USER && it.content.contains("current_user_input")
        }
        // Группы образов непрерывны и пронумерованы кумулятивно; заметка опциональна.
        val refs = envelopeOf(current)["pinnedImages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, refs.size)
        assertEquals("1-2", refs[0]["images"]!!.jsonPrimitive.content)
        assertEquals("пара", refs[0]["note"]!!.jsonPrimitive.content)
        assertEquals("3-3", refs[1]["images"]!!.jsonPrimitive.content)
        assertNull("у закрепа без заметки ключа note быть не должно", refs[1]["note"])
    }

    @Test
    fun pinUnknownAddress_amendsToolResultToFailure() = runTest {
        val changes = mutableListOf<PinChange>()
        val transcript = mutableListOf<ChatMessage>()
        val client = pinClient("""{"message_id":"t9:0"}""")
        agent(client).stream(
            "m", null, emptyList(), "вопрос",
            onTurnMessages = { transcript += it },
            onPinChange = { changes += it },
        ).toList()

        val toolMessage = transcript.single { it.role == ChatRole.TOOL }
        assertTrue(toolMessage.content.contains("не найдено"))
        assertTrue(changes.isEmpty())
    }

    @Test
    fun pinCurrentUserMessage_marksCurrentEnvelope() = runTest {
        val client = pinClient("""{"message_id":"current:0","note":"фото для сверки"}""")
        agent(client).stream("m", null, emptyList(), "вопрос").toList()

        val current = client.seenMessages[1].first { it.role == ChatRole.USER && it.content.contains("current_user_input") }
        val envelope = envelopeOf(current)
        assertEquals("current:0", envelope["id"]!!.jsonPrimitive.content)
        assertEquals("true", envelope["pinned"]!!.jsonPrimitive.content)
        assertEquals("фото для сверки", envelope["pinNote"]!!.jsonPrimitive.content)
    }

    @Test
    fun pinOfTranscriptMessage_ridesTranscriptOfCurrentTurn() = runTest {
        val client = pinClient("""{"message_id":"current:1"}""")
        var transcript = emptyList<ChatMessage>()
        agent(client).stream("m", null, emptyList(), "вопрос", onTurnMessages = { transcript = it }).toList()

        // Закрепление сообщения транскрипта сохраняется флагом самого сообщения:
        // вызывающий слой записывает транскрипт как есть.
        assertEquals(MessagePin(), transcript.first { it.role == ChatRole.ASSISTANT }.pin)
    }

    @Test
    fun unpinRestoresBudgetTrimming() = runTest {
        val image = ImagePart(imageBase64 = "QUJD")
        val history = (0..MAX_IMAGE_MESSAGES + 1).map { index ->
            ChatMessage(
                ChatRole.USER,
                "old $index",
                images = listOf(image),
                id = "t$index:0",
                pin = if (index == 0) MessagePin("было") else null,
            )
        }
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(ChatChunk.ToolCall(id = "c1", name = "message_unpin", argumentsJson = """{"message_id":"t0:0"}"""), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("done"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val changes = mutableListOf<PinChange>()
        agent(client).stream("m", null, history, "вопрос", onPinChange = { changes += it }).toList()

        val byId = client.seenMessages[1].filter { it.role == ChatRole.USER }
            .associateBy { envelopeOf(it)["id"]?.jsonPrimitive?.content }
        // После открепления сообщение снова подлежит бюджету: старое — усечено.
        assertNull(envelopeOf(byId.getValue("t0:0"))["pinned"])
        assertTrue(byId.getValue("t0:0").images.isEmpty())
        assertEquals(listOf(PinChange("t0:0", pinned = false, note = null)), changes)
    }

    @Test
    fun onPinChangeFailure_turnContinuesWithWarning() = runTest {
        val history = listOf(ChatMessage(ChatRole.USER, "фото", id = "t0:0"))
        val transcript = mutableListOf<ChatMessage>()
        val client = pinClient("""{"message_id":"t0:0"}""")
        agent(client).stream(
            "m", null, history, "вопрос",
            onTurnMessages = { transcript += it },
            onPinChange = { throw java.io.IOException("база недоступна") },
        ).toList()

        // Сбой сохранения не роняет ход: модель честно предупреждена,
        // закрепление остаётся в живой истории.
        val tool = transcript.single { it.role == ChatRole.TOOL }
        assertTrue(tool.content.contains("не сохранено"))
        val secondRound = client.seenMessages[1].first { it.role == ChatRole.USER && it.content.contains("history") }
        assertEquals("true", envelopeOf(secondRound)["pinned"]!!.jsonPrimitive.content)
    }

    @Test
    fun unpinOfUnpinnedMessage_saysNotPinned() = runTest {
        val history = listOf(ChatMessage(ChatRole.USER, "просто текст", id = "t0:0"))
        val changes = mutableListOf<PinChange>()
        val transcript = mutableListOf<ChatMessage>()
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(ChatChunk.ToolCall(id = "c1", name = "message_unpin", argumentsJson = """{"message_id":"t0:0"}"""), ChatChunk.Finish("tool_calls")),
                    listOf(ChatChunk.Content("done"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        agent(client).stream(
            "m", null, history, "вопрос",
            onTurnMessages = { transcript += it },
            onPinChange = { changes += it },
        ).toList()

        assertTrue(transcript.single { it.role == ChatRole.TOOL }.content.contains("не было закреплено"))
        assertTrue(changes.isEmpty())
    }

    @Test
    fun pinCurrentIndexOutOfBounds_failsHonestly() = runTest {
        val transcript = mutableListOf<ChatMessage>()
        val client = pinClient("""{"message_id":"current:9"}""")
        agent(client).stream("m", null, emptyList(), "вопрос", onTurnMessages = { transcript += it }).toList()

        assertTrue(transcript.single { it.role == ChatRole.TOOL }.content.contains("не найдено"))
    }
}
