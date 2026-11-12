package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.MessagePin
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.model.ToolCallRef

/** Адресация и метки закрепления в конвертах истории запроса модели. */
class PinContextTest {

    private fun envelopeOf(message: ChatMessage): JsonObject =
        Json.parseToJsonElement(message.content).jsonObject

    private fun transport(transcript: List<ChatMessage>, currentUserIndex: Int, liveToolStatuses: Map<Int, Boolean> = emptyMap()) =
        buildRuntimeMessages(
            transcript = transcript,
            currentUserIndex = currentUserIndex,
            ownerInstructions = null,
            promptContext = emptyList(),
            currentInput = ModelUserInput("запрос"),
            skillInstructions = emptyList(),
            liveToolStatuses = liveToolStatuses,
        )

    @Test
    fun historyEnvelope_carriesAddressAndPin() {
        val pinned = ChatMessage(ChatRole.USER, "фото", id = "turn-1:0", pin = MessagePin("референс"))
        val wire = transport(listOf(pinned, ChatMessage(ChatRole.USER, "сверь")), currentUserIndex = 1)

        val envelope = envelopeOf(wire[1])
        assertEquals("history", envelope["kind"]!!.jsonPrimitive.content)
        assertEquals("turn-1:0", envelope["id"]!!.jsonPrimitive.content)
        assertEquals("true", envelope["pinned"]!!.jsonPrimitive.content)
        assertEquals("референс", envelope["pinNote"]!!.jsonPrimitive.content)
    }

    @Test
    fun historyEnvelope_withoutPinOrId_hasNoAddressFields() {
        val wire = transport(listOf(ChatMessage(ChatRole.USER, "привет"), ChatMessage(ChatRole.USER, "сверь")), currentUserIndex = 1)

        val envelope = envelopeOf(wire[1])
        assertNull(envelope["id"])
        assertNull(envelope["pinned"])
    }

    @Test
    fun currentEnvelope_hasCurrentZeroAddress() {
        val current = ChatMessage(ChatRole.USER, "закрепи фото", pin = MessagePin("референс"))
        val wire = transport(listOf(current), currentUserIndex = 0)

        val envelope = envelopeOf(wire[1])
        assertEquals("current_user_input", envelope["kind"]!!.jsonPrimitive.content)
        assertEquals("current:0", envelope["id"]!!.jsonPrimitive.content)
        assertEquals("true", envelope["pinned"]!!.jsonPrimitive.content)
    }

    @Test
    fun currentTurnToolResults_getPositionalAddresses() {
        val call = ToolCallRef("c1", "ui_screenshot", "{}")
        val transcript = listOf(
            ChatMessage(ChatRole.USER, "запрос"),
            ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
            ChatMessage(ChatRole.TOOL, "снимок", toolCallId = "c1", pin = MessagePin("снимок экрана")),
        )
        val wire = transport(transcript, currentUserIndex = 0, liveToolStatuses = mapOf(2 to true))

        val toolEnvelope = envelopeOf(wire.single { it.role == ChatRole.TOOL })
        assertEquals("tool_result", toolEnvelope["kind"]!!.jsonPrimitive.content)
        assertEquals("current:2", toolEnvelope["id"]!!.jsonPrimitive.content)
        assertEquals("true", toolEnvelope["pinned"]!!.jsonPrimitive.content)
        assertEquals("снимок экрана", toolEnvelope["pinNote"]!!.jsonPrimitive.content)
    }

    @Test
    fun pinnedMessages_keepMediaThroughSanitize() {
        val image = ImagePart(imageBase64 = "QUJD")
        val audio = AudioPart(wavBase64 = "V0FW", durationSec = 1)
        val pinnedAssistant = ChatMessage(ChatRole.ASSISTANT, "старый ответ", images = listOf(image), audio = audio, pin = MessagePin())
        val pinnedTool = ChatMessage(ChatRole.TOOL, "старый результат", toolCallId = "lost", images = listOf(image), audio = audio, pin = MessagePin())
        val wire = transport(
            listOf(pinnedAssistant, pinnedTool, ChatMessage(ChatRole.USER, "сверь")),
            currentUserIndex = 2,
        )

        val assistant = wire.first { it.role == ChatRole.ASSISTANT }
        val tool = wire.first {
            it.role == ChatRole.USER && envelopeOf(it)["sourceRole"]?.jsonPrimitive?.content == "tool"
        }
        assertEquals(listOf(image), assistant.images)
        assertEquals(audio, assistant.audio)
        assertEquals(listOf(image), tool.images)
        assertEquals(audio, tool.audio)
    }

    @Test
    fun unpinnedMessages_loseAssistantMediaAsBefore() {
        val image = ImagePart(imageBase64 = "QUJD")
        val old = ChatMessage(ChatRole.ASSISTANT, "старый ответ", images = listOf(image), audio = AudioPart("V0FW", durationSec = 1))
        val wire = transport(listOf(old, ChatMessage(ChatRole.USER, "сверь")), currentUserIndex = 1)

        val assistant = wire.first { it.role == ChatRole.ASSISTANT }
        assertTrue(assistant.images.isEmpty())
        assertNull(assistant.audio)
    }
}
