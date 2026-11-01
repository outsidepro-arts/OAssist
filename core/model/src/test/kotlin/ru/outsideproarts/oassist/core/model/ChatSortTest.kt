package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSortTest {

    private fun chat(
        id: String,
        title: String = id,
        updatedAt: Long = 0L,
    ) = ChatSummary(
        id = id,
        title = title,
        kind = ChatKind.REGULAR,
        mode = AssistMode.CHAT,
        createdAt = 0L,
        updatedAt = updatedAt,
    )

    @Test
    fun `date desc puts newest first`() {
        val old = chat("old", updatedAt = 100)
        val mid = chat("mid", updatedAt = 200)
        val new = chat("new", updatedAt = 300)

        val sorted = listOf(old, new, mid).sortedByOrder(ChatSortOrder.DATE_DESC)

        assertEquals(listOf(new, mid, old), sorted)
    }

    @Test
    fun `date asc puts oldest first`() {
        val old = chat("old", updatedAt = 100)
        val mid = chat("mid", updatedAt = 200)
        val new = chat("new", updatedAt = 300)

        val sorted = listOf(new, mid, old).sortedByOrder(ChatSortOrder.DATE_ASC)

        assertEquals(listOf(old, mid, new), sorted)
    }

    @Test
    fun `title sort is alphabetical case insensitive`() {
        val ab = chat("ab", title = "банан")
        val aa = chat("aa", title = "Ананас")
        val ac = chat("ac", title = "вишня")

        val sorted = listOf(ab, ac, aa).sortedByOrder(ChatSortOrder.TITLE)

        assertEquals(listOf(aa, ab, ac), sorted)
    }

    @Test
    fun `sort is stable for equal keys`() {
        val first = chat("first", title = "Одинаково", updatedAt = 100)
        val second = chat("second", title = "одинаково", updatedAt = 100)

        val byDate = listOf(second, first).sortedByOrder(ChatSortOrder.DATE_DESC)
        val byTitle = listOf(second, first).sortedByOrder(ChatSortOrder.TITLE)

        assertEquals(listOf(second, first), byDate)
        assertEquals(listOf(second, first), byTitle)
    }

    @Test
    fun `empty list stays empty`() {
        val empty = emptyList<ChatSummary>()

        assertEquals(empty, empty.sortedByOrder(ChatSortOrder.DATE_DESC))
        assertEquals(empty, empty.sortedByOrder(ChatSortOrder.DATE_ASC))
        assertEquals(empty, empty.sortedByOrder(ChatSortOrder.TITLE))
    }

    @Test
    fun `fromName parses known orders`() {
        assertEquals(ChatSortOrder.DATE_DESC, ChatSortOrder.fromName("DATE_DESC"))
        assertEquals(ChatSortOrder.DATE_ASC, ChatSortOrder.fromName("DATE_ASC"))
        assertEquals(ChatSortOrder.TITLE, ChatSortOrder.fromName("TITLE"))
    }

    @Test
    fun `fromName falls back to date desc on garbage`() {
        assertEquals(ChatSortOrder.DATE_DESC, ChatSortOrder.fromName(null))
        assertEquals(ChatSortOrder.DATE_DESC, ChatSortOrder.fromName(""))
        assertEquals(ChatSortOrder.DATE_DESC, ChatSortOrder.fromName("BOGUS"))
    }
}
