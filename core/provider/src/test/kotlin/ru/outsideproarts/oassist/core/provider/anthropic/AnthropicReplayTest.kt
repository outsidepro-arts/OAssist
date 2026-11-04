package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.*

class AnthropicReplayTest {
    private val blocks = """[{"type":"thinking","thinking":"","signature":"signed"},{"type":"text","text":"original"},{"type":"tool_use","id":"c1","name":"ping","input":{"x":1}}]"""
    private val message = ChatMessage(ChatRole.ASSISTANT, "original",
        toolCalls = listOf(ToolCallRef("c1", "ping", """{"x":1}""")),
        reasoning = ReasoningBlock(text = "display only", anthropicReplay = AnthropicReplay("origin", blocks)))

    @Test fun editedDomainContentAndRemappedCallsDoNotRestoreStaleSnapshot() {
        for (edited in listOf(message.copy(content = "edited"),
            message.copy(toolCalls = listOf(message.toolCalls!!.single().copy(id = "remapped"))),
            message.copy(toolCalls = null))) {
            val wire = mapAnthropicMessages(listOf(edited), true, "origin").second.single().content.toString()
            assertFalse(wire.contains("signed"))
            assertFalse(wire.contains("display only"))
            if (edited.content == "edited") { assertTrue(wire.contains("edited")); assertFalse(wire.contains("original")) }
            if (edited.toolCalls?.single()?.id == "remapped") { assertTrue(wire.contains("remapped")); assertFalse(wire.contains("c1")) }
        }
        assertEquals(Json.parseToJsonElement(blocks), mapAnthropicMessages(listOf(message), true, "origin").second.single().content)
    }

    @Test fun oldSingleSignedBlockIsReadWithoutInventingMissingBlocks() {
        val legacy = Json.decodeFromString<ChatMessage>("""{"role":"ASSISTANT","content":"answer","reasoning":{"text":"","signature":"legacy"}}""")
        assertNull(legacy.reasoning!!.anthropicReplay)
        val wire = mapAnthropicMessages(listOf(legacy), true).second.single().content.jsonArray
        assertEquals("", wire[0].jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("legacy", wire[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals(2, wire.size)
    }

    @Test fun initialOpaqueFieldsAndSplitDeltasRemainExact() {
        val accumulator = AnthropicContentAccumulator()
        accumulator.start(0, Json.parseToJsonElement("""{"type":"thinking","thinking":"initial","signature":"s","unknown":{"future":true}}""").jsonObject)
        accumulator.delta(0, AnthropicDelta(type = "thinking_delta", thinking = "\nnext"))
        accumulator.delta(0, AnthropicDelta(type = "signature_delta", signature = "+/="))
        accumulator.stop(0)
        assertEquals(Json.parseToJsonElement("""[{"type":"thinking","thinking":"initial\nnext","signature":"s+/=","unknown":{"future":true}}]"""), accumulator.complete())
    }

    @Test fun missingSignatureOrUnclosedBlockCannotBecomeReplay() {
        val unsigned = AnthropicContentAccumulator()
        unsigned.start(0, Json.parseToJsonElement("""{"type":"thinking","thinking":"partial","signature":""}""").jsonObject)
        unsigned.stop(0)
        assertTrue(runCatching { unsigned.complete() }.exceptionOrNull() is ru.outsideproarts.oassist.core.provider.ProviderError.Protocol)
        val unclosed = AnthropicContentAccumulator()
        unclosed.start(0, Json.parseToJsonElement("""{"type":"redacted_thinking","data":"opaque"}""").jsonObject)
        assertTrue(runCatching { unclosed.complete() }.exceptionOrNull() is ru.outsideproarts.oassist.core.provider.ProviderError.Protocol)
    }
}
