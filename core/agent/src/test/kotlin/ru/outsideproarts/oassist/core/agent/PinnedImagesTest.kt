package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.MessagePin

class PinnedImagesTest {

    private fun img(id: String) = ImagePart(imageBase64 = id)

    @Test
    fun pinnedHistoryImagesMoveToCurrentWithNotes() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "старое", images = listOf(img("A")), id = "t0:0", pin = MessagePin("референс")),
            ChatMessage(ChatRole.USER, "незакреплённое", images = listOf(img("B")), id = "t1:0"),
            ChatMessage(ChatRole.USER, "текущее", images = listOf(img("C")), id = "current:0"),
        )

        val hoisted = hoistPinnedImages(messages, 2)

        // Референс — первым в текущем сообщении, за ним — своё изображение.
        assertEquals(listOf("A", "C"), hoisted.messages[2].images.map { it.imageBase64 })
        assertEquals(listOf(HoistedPinnedSource("t0:0", "референс", 1)), hoisted.sources)
        // Историческое сообщение остаётся на месте: с заметкой, без образа.
        assertTrue(hoisted.messages[0].images.isEmpty())
        assertTrue(hoisted.messages[0].content.startsWith("старое"))
        assertTrue(hoisted.messages[0].content.contains(PINNED_IMAGE_MOVED_NOTE))
        // Незакреплённая история не тронута.
        assertEquals(listOf("B"), hoisted.messages[1].images.map { it.imageBase64 })
        // Исходный транскрипт не изменён (переезд — на копиях).
        assertEquals(listOf("A"), messages[0].images.map { it.imageBase64 })
    }

    @Test
    fun severalPinnedMoveInOrder() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "первый", images = listOf(img("A")), id = "t0:0", pin = MessagePin()),
            ChatMessage(ChatRole.USER, "второй", images = listOf(img("B")), id = "t1:0", pin = MessagePin()),
            ChatMessage(ChatRole.USER, "текущее", id = "current:0"),
        )

        val hoisted = hoistPinnedImages(messages, 2)

        assertEquals(listOf("A", "B"), hoisted.messages[2].images.map { it.imageBase64 })
        assertEquals(
            listOf(HoistedPinnedSource("t0:0", null, 1), HoistedPinnedSource("t1:0", null, 1)),
            hoisted.sources,
        )
    }

    @Test
    fun pinnedCurrentMessageKeepsItsImagesInPlace() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "текущее", images = listOf(img("C")), id = "current:0", pin = MessagePin()),
        )

        val hoisted = hoistPinnedImages(messages, 0)

        assertEquals(listOf("C"), hoisted.messages[0].images.map { it.imageBase64 })
        assertTrue(hoisted.sources.isEmpty())
    }

    @Test
    fun pinnedWithoutImagesAndUnpinnedAreUntouched() {
        val pinnedText = ChatMessage(ChatRole.USER, "текст", id = "t0:0", pin = MessagePin())
        val plain = ChatMessage(ChatRole.USER, "просто", images = listOf(img("B")), id = "t1:0")
        val messages = listOf(pinnedText, plain, ChatMessage(ChatRole.USER, "текущее", id = "current:0"))

        val hoisted = hoistPinnedImages(messages, 2)

        assertEquals(pinnedText, hoisted.messages[0])
        assertEquals(plain, hoisted.messages[1])
        assertTrue(hoisted.sources.isEmpty())
    }

    @Test
    fun emptySourceContentLeavesOnlyMovedNote() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "", images = listOf(img("A")), id = "t0:0", pin = MessagePin()),
            ChatMessage(ChatRole.USER, "текущее", id = "current:0"),
        )

        val hoisted = hoistPinnedImages(messages, 1)

        // Заметка — единственный текст источника, без висящего разделителя.
        assertEquals(PINNED_IMAGE_MOVED_NOTE, hoisted.messages[0].content)
        assertEquals(listOf("A"), hoisted.messages[1].images.map { it.imageBase64 })
    }

    @Test
    fun sourceAudioStaysWhenItsImagesMove() {
        val audio = AudioPart(wavBase64 = "AAAA", durationSec = 3)
        val messages = listOf(
            ChatMessage(ChatRole.USER, "голосовое", audio = audio, images = listOf(img("A")), id = "t0:0", pin = MessagePin()),
            ChatMessage(ChatRole.USER, "текущее", images = listOf(img("C")), id = "current:0"),
        )

        val hoisted = hoistPinnedImages(messages, 1)

        // Переезжают только образы: голосовое сообщение источника остаётся на месте.
        assertEquals(audio, hoisted.messages[0].audio)
        assertTrue(hoisted.messages[0].images.isEmpty())
        assertEquals(listOf("A", "C"), hoisted.messages[1].images.map { it.imageBase64 })
    }

    @Test
    fun currentTurnMessagesAreNotHoisted() {
        val pinnedTurnMessage = ChatMessage(
            ChatRole.USER, "раунд текущего хода", images = listOf(img("B")), id = "current:2", pin = MessagePin(),
        )
        val messages = listOf(
            ChatMessage(ChatRole.USER, "история", images = listOf(img("A")), id = "t0:0", pin = MessagePin("реф")),
            ChatMessage(ChatRole.USER, "текущее", id = "current:0"),
            pinnedTurnMessage,
        )

        val hoisted = hoistPinnedImages(messages, 1)

        // Сообщения текущего хода остаются на месте: их образы и так в свежей позиции.
        assertEquals(listOf("A"), hoisted.messages[1].images.map { it.imageBase64 })
        assertEquals(pinnedTurnMessage, hoisted.messages[2])
        assertEquals(listOf(HoistedPinnedSource("t0:0", "реф", 1)), hoisted.sources)
    }

    @Test
    fun sourceWithoutAddressKeepsImagesInPlace() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "без адреса", images = listOf(img("A")), id = null, pin = MessagePin()),
            ChatMessage(ChatRole.USER, "текущее", id = "current:0"),
        )

        val hoisted = hoistPinnedImages(messages, 1)

        // Инвариант: каждый переехавший образ идентифицирован в sources.
        assertEquals(listOf("A"), hoisted.messages[0].images.map { it.imageBase64 })
        assertTrue(hoisted.sources.isEmpty())
    }

    @Test
    fun sourcesCarryNotesAndImageCounts() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "две", images = listOf(img("A"), img("B")), id = "t0:0", pin = MessagePin("реф")),
            ChatMessage(ChatRole.USER, "текущее", id = "current:0"),
        )

        val hoisted = hoistPinnedImages(messages, 1)

        // Конверт связывает образы с закрепом: заметка и число образов источника.
        assertEquals(listOf(HoistedPinnedSource("t0:0", "реф", 2)), hoisted.sources)
        assertEquals(listOf("A", "B"), hoisted.messages[1].images.map { it.imageBase64 })
    }

    @Test
    fun currentUserIndexOutOfBounds_fails() {
        val messages = listOf(ChatMessage(ChatRole.USER, "единственное", id = "t0:0"))

        assertThrows(IllegalArgumentException::class.java) { hoistPinnedImages(messages, 1) }
    }
}
