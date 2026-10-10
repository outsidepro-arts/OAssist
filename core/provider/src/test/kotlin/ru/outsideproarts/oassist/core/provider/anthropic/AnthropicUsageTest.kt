package ru.outsideproarts.oassist.core.provider.anthropic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ProviderJson

class AnthropicUsageTest {

    private val json = ProviderJson

    @Test
    fun messageStart_eventCarriesInputUsage() {
        val data = """
            {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude","usage":{"input_tokens":25,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":1}}}
        """.trimIndent()
        val event = json.decodeFromString(AnthropicMessageStartEvent.serializer(), data)
        val usage = event.message?.usage
        assertNotNull(usage)
        assertEquals(25L, usage!!.inputTokens)
        assertEquals(100L, usage.cacheReadInputTokens)
    }

    @Test
    fun messageDelta_eventCarriesCumulativeOutputUsage() {
        val data = """
            {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}
        """.trimIndent()
        val event = json.decodeFromString(AnthropicMessageDeltaEvent.serializer(), data)
        assertEquals("end_turn", event.delta?.stopReason)
        assertEquals(15L, event.usage?.outputTokens)
    }

    @Test
    fun usage_fromStartAndDelta_buildsCanonicalUsage() {
        // Как в клиенте: input из message_start, output кумулятивно из message_delta.
        val start = json.decodeFromString(
            AnthropicMessageStartEvent.serializer(),
            """{"type":"message_start","message":{"usage":{"input_tokens":25,"cache_read_input_tokens":100}}}""",
        )
        val delta = json.decodeFromString(
            AnthropicMessageDeltaEvent.serializer(),
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}""",
        )
        val input = ru.outsideproarts.oassist.core.model.ProviderUsage.of(
            inputTokens = start.message?.usage?.inputTokens,
            cachedTokens = start.message?.usage?.cacheReadInputTokens,
        )
        val merged = ru.outsideproarts.oassist.core.model.mergeUsage(
            input,
            ru.outsideproarts.oassist.core.model.ProviderUsage.of(outputTokens = delta.usage?.outputTokens),
        )!!
        assertEquals(25L, merged.inputTokens)
        assertEquals(15L, merged.outputTokens)
        assertEquals(40L, merged.totalTokens)
        assertEquals(100L, merged.cachedTokens)
    }
}
