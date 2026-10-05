package ru.outsideproarts.oassist.core.provider.anthropic

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ChatChunk

class AnthropicStreamAccumulatorTest {

    @Test
    fun singleToolCall_assemblesAcrossDeltas() {
        val acc = AnthropicStreamAccumulator()
        acc.contentBlockStart(index = 1, blockType = "tool_use", id = "toolu_1", name = "device_state")
        acc.inputJsonDelta(1, "{\"deta")
        acc.inputJsonDelta(1, "iled\":true}")
        val calls = acc.build()
        assertEquals(
            listOf(ChatChunk.ToolCall(id = "toolu_1", name = "device_state", argumentsJson = "{\"detailed\":true}", index = 1)),
            calls,
        )
    }

    @Test
    fun textBlockStart_isIgnored() {
        val acc = AnthropicStreamAccumulator()
        acc.contentBlockStart(index = 0, blockType = "text", id = null, name = null)
        assertEquals(emptyList<ChatChunk.ToolCall>(), acc.build())
    }

    @Test
    fun twoCalls_keepIndexOrder() {
        val acc = AnthropicStreamAccumulator()
        acc.contentBlockStart(0, "tool_use", "toolu_a", "device_state")
        acc.contentBlockStart(1, "tool_use", "toolu_b", "location_get")
        acc.inputJsonDelta(1, "{\"x\":1}")
        acc.inputJsonDelta(0, "{}")
        val calls = acc.build()
        assertEquals(2, calls.size)
        assertEquals("toolu_a", calls[0].id)
        assertEquals("toolu_b", calls[1].id)
        assertEquals("{\"x\":1}", calls[1].argumentsJson)
    }

    @Test
    fun unnamedCall_failsClosedWithEmptyName() {
        val acc = AnthropicStreamAccumulator()
        acc.inputJsonDelta(2, "{}")
        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("call_2", calls[0].id)
        assertEquals("", calls[0].name)
    }

    @Test
    fun emptyArgs_defaultToEmptyObject() {
        val acc = AnthropicStreamAccumulator()
        acc.contentBlockStart(0, "tool_use", "toolu_1", "device_state")
        assertEquals("{}", acc.build().single().argumentsJson)
    }
}
