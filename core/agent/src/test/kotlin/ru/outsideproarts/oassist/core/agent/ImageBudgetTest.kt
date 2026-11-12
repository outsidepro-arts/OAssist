package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.model.MessagePin

class ImageBudgetTest {

    private fun img(id: String) = ImagePart(imageBase64 = id)

    private fun withImages(content: String, ids: List<String>) =
        ChatMessage(ChatRole.USER, content, images = ids.map { img(it) })

    @Test
    fun keepsLastImageMessages_dropsOlder() {
        val messages = listOf(
            withImages("one", listOf("a")),
            ChatMessage(ChatRole.USER, "plain"),
            withImages("two", listOf("b")),
            withImages("three", listOf("c")),
            withImages("four", listOf("d")),
            withImages("five", listOf("e")),
        )

        val out = enforceImageBudget(messages, maxImages = 2)

        assertEquals(messages.size, out.size)
        assertTrue(out[0].images.isEmpty())
        assertTrue(out[0].content.contains(IMAGE_REMOVED_NOTE))
        assertTrue(out[2].images.isEmpty())
        assertTrue(out[3].images.isEmpty())
        assertEquals(listOf("d"), out[4].images.map { it.imageBase64 })
        assertEquals(listOf("e"), out[5].images.map { it.imageBase64 })
    }

    @Test
    fun orderPreserved() {
        val messages = listOf(
            withImages("one", listOf("a")),
            withImages("two", listOf("b")),
        )

        val out = enforceImageBudget(messages, maxImages = 1)

        assertEquals(listOf("one", "two"), out.map { it.content.substringBefore("\n") })
        assertTrue(out[0].images.isEmpty())
        assertEquals(1, out[1].images.size)
    }

    @Test
    fun emptyContent_getsNoteOnly() {
        val out = enforceImageBudget(
            listOf(withImages("", listOf("a")), withImages("two", listOf("b"))),
            maxImages = 1,
        )

        assertEquals(IMAGE_REMOVED_NOTE, out[0].content)
    }

    @Test
    fun noImages_unchanged() {
        val messages = listOf(ChatMessage(ChatRole.USER, "plain"))

        assertEquals(messages, enforceImageBudget(messages))
    }

    @Test
    fun pinnedImages_keptEvenWhenOld() {
        val pinned = withImages("pinned old", listOf("p")).copy(pin = MessagePin("референс"))
        val messages = listOf(
            pinned,
            withImages("one", listOf("a")),
            withImages("two", listOf("b")),
            withImages("three", listOf("c")),
        )

        val out = enforceImageBudget(messages, maxImages = 2)

        // Закреплённое не усекается и не занимает бюджет обычных сообщений.
        assertEquals(listOf("p"), out[0].images.map { it.imageBase64 })
        assertEquals(MessagePin("референс"), out[0].pin)
        assertTrue(out[1].images.isEmpty())
        assertEquals(listOf("b"), out[2].images.map { it.imageBase64 })
        assertEquals(listOf("c"), out[3].images.map { it.imageBase64 })
    }

    @Test
    fun pinnedImages_doNotConsumeBudget() {
        val messages = (1..5).map {
            withImages("m$it", listOf("i$it")).copy(pin = MessagePin())
        }

        val out = enforceImageBudget(messages, maxImages = 1)

        assertTrue(out.all { it.images.size == 1 })
        assertTrue(out.none { it.content.contains(IMAGE_REMOVED_NOTE) })
    }
}