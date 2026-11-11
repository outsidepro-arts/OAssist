package ru.outsideproarts.oassist.core.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollMemoryTest {

    // Память — глобальный синглтон: каждый тест работает со своими ключами
    // и подчищает их за собой.
    private val usedKeys = mutableListOf<String>()

    private fun key(prefix: String): String = "$prefix-${usedKeys.size}".also { usedKeys.add(it) }

    @After
    fun cleanup() {
        usedKeys.forEach { ChatScrollMemory.remove(it) }
    }

    @Test
    fun positionsOfDifferentChatsDoNotMix() {
        val chatA = key("chat-a")
        val chatB = key("chat-b")
        ChatScrollMemory.save(chatA, ChatScrollPosition(3, 40))
        ChatScrollMemory.save(chatB, ChatScrollPosition(7, 0))

        assertEquals(ChatScrollPosition(3, 40), ChatScrollMemory.restore(chatA))
        assertEquals(ChatScrollPosition(7, 0), ChatScrollMemory.restore(chatB))
    }

    @Test
    fun savingAgainReplacesPosition() {
        val chat = key("chat")
        ChatScrollMemory.save(chat, ChatScrollPosition(3, 40))
        ChatScrollMemory.save(chat, ChatScrollPosition(5, 12))

        assertEquals(ChatScrollPosition(5, 12), ChatScrollMemory.restore(chat))
    }

    @Test
    fun restoreWithoutSaveReturnsNull() {
        assertNull(ChatScrollMemory.restore(key("unknown-chat")))
    }

    @Test
    fun removeDropsOnlyThatChat() {
        val chatA = key("chat-a")
        val chatB = key("chat-b")
        ChatScrollMemory.save(chatA, ChatScrollPosition(3, 40))
        ChatScrollMemory.save(chatB, ChatScrollPosition(7, 0))

        ChatScrollMemory.remove(chatA)

        assertNull(ChatScrollMemory.restore(chatA))
        assertEquals(ChatScrollPosition(7, 0), ChatScrollMemory.restore(chatB))
    }
}
