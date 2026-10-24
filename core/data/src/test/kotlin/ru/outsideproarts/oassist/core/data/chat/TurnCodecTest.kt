package ru.outsideproarts.oassist.core.data.chat

import java.io.File
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.ReasoningBlock
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.model.ToolCallRef
import ru.outsideproarts.oassist.core.model.WorkEvent

class TurnCodecTest {

    private lateinit var root: File
    private lateinit var media: ChatMediaStore

    @Before
    fun setUp() {
        root = File.createTempFile("oassist-turn", null).let { it.delete(); it.mkdirs(); it }
        media = ChatMediaStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    private fun fullTurn() = StoredTurn(
        userText = "Какой статус?",
        userAudio = AudioPart(wavBase64 = base64("WAV"), durationSec = 2),
        userImages = listOf(ImagePart(imageBase64 = base64("IMG"), width = 100, height = 50)),
        work = listOf(
            WorkEvent.Thought("Смотрю…"),
            WorkEvent.Tool(
                name = "device_state",
                output = "torch=off",
                isError = false,
                images = listOf(ImagePart(imageBase64 = base64("SHOT"))),
            ),
        ),
        answer = "Фонарик выключен",
        usage = ProviderUsage.of(inputTokens = 11, outputTokens = 7, costUsd = 0.001),
        turn = listOf(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Смотрю…",
                toolCalls = listOf(ToolCallRef(id = "call_1", name = "device_state", argumentsJson = "{}")),
                reasoning = ReasoningBlock(text = "сперва проверю фонарик", signature = "sig_abc"),
            ),
            ChatMessage(role = ChatRole.TOOL, content = "torch=off", toolCallId = "call_1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "Фонарик выключен"),
        ),
    )

    @Test
    fun roundTrip_preservesEverything() {
        val payload = TurnCodec.encode(fullTurn(), chatId = "c1", turnId = "t1", media = media)
        val restored = TurnCodec.decode(payload, media)!!

        assertEquals("Какой статус?", restored.userText)
        assertEquals(base64("WAV"), restored.userAudio?.wavBase64)
        assertEquals(2, restored.userAudio?.durationSec)
        assertEquals(base64("IMG"), restored.userImages.single().imageBase64)
        assertEquals(100, restored.userImages.single().width)
        // Хронологическое полотно: мысль → тул (порядок сохраняется).
        assertEquals(2, restored.work.size)
        assertEquals("Смотрю…", (restored.work[0] as WorkEvent.Thought).text)
        val tool = restored.work[1] as WorkEvent.Tool
        assertEquals("device_state", tool.name)
        assertEquals("torch=off", tool.output)
        assertEquals(base64("SHOT"), tool.images.single().imageBase64)
        assertEquals("Фонарик выключен", restored.answer)
        assertEquals(11L, restored.usage?.inputTokens)
        assertEquals(0.001, restored.usage?.costUsd)
        assertEquals(3, restored.turn.size)
        assertEquals("call_1", restored.turn[0].toolCalls?.single()?.id)
        assertEquals("call_1", restored.turn[1].toolCallId)
        // Мысли ассистента переживают перезапись (replay Anthropic/Responses).
        assertEquals("сперва проверю фонарик", restored.turn[0].reasoning?.text)
        assertEquals("sig_abc", restored.turn[0].reasoning?.signature)
        assertNull(restored.turn[2].reasoning)
    }

    @Test
    fun decode_legacyThoughtTools_migratesToWork() {
        // Ходы до WorkEvent: плоские thought+tools без поля work.
        val legacy = """{"userText":"вопрос","thought":"считаю","tools":[{"name":"device_state","output":"ok"}],"answer":"ответ"}"""
        val restored = TurnCodec.decode(legacy, media)!!

        assertEquals(2, restored.work.size)
        assertEquals("считаю", (restored.work[0] as WorkEvent.Thought).text)
        assertEquals("device_state", (restored.work[1] as WorkEvent.Tool).name)
        assertEquals("ответ", restored.answer)
    }

    @Test
    fun encode_writesMediaFilesNotBase64() {
        val payload = TurnCodec.encode(fullTurn(), chatId = "c1", turnId = "t1", media = media)

        assertFalse(payload.contains(base64("WAV")))
        assertTrue(payload.contains("c1/t1_a.wav"))
        assertTrue(File(root, "c1/t1_a.wav").isFile)
        assertTrue(File(root, "c1/t1_u0.jpg").isFile)
    }

    @Test
    fun decode_missingMediaFile_dropsOnlyMedia() {
        val payload = TurnCodec.encode(fullTurn(), chatId = "c1", turnId = "t1", media = media)
        File(root, "c1/t1_a.wav").delete()

        val restored = TurnCodec.decode(payload, media)!!

        assertNull(restored.userAudio)
        assertEquals("Какой статус?", restored.userText)
        assertEquals(1, restored.userImages.size)
    }

    @Test
    fun decode_corruptPayload_returnsNull() {
        assertNull(TurnCodec.decode("{не json", media))
        assertNull(TurnCodec.decode("", media))
    }

    @Test
    fun decode_toleratesUnknownFieldsAndMissingOptionals() {
        val legacy = """{"userText":"привет","answer":"здравствуйте","futureField":42}"""
        val restored = TurnCodec.decode(legacy, media)!!
        assertEquals("привет", restored.userText)
        assertEquals("здравствуйте", restored.answer)
        assertTrue(restored.turn.isEmpty())
    }

    @Test
    fun modelMessages_afterRoundTrip_keepToolPairs() {
        val payload = TurnCodec.encode(fullTurn(), chatId = "c1", turnId = "t1", media = media)
        val messages = TurnCodec.decode(payload, media)!!.modelMessages()

        assertEquals(4, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals(ChatRole.TOOL, messages[2].role)
        assertEquals("call_1", messages[2].toolCallId)
    }
}
