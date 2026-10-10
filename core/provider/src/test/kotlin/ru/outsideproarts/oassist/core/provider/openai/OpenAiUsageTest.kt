package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ProviderJson

class OpenAiUsageTest {

    private val json = ProviderJson

    @Test
    fun usage_chunkWithEmptyChoices_parsesUsage() {
        val data = """
            {"id":"x","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":30}}}
        """.trimIndent()
        val dto = json.decodeFromString(ChatResponseDto.serializer(), data)
        assertEquals(emptyList<ChatChoiceDto>(), dto.choices)
        val usage = dto.usageOrCost()!!
        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(120L, usage.totalTokens)
        assertEquals(30L, usage.cachedTokens)
        assertNull(usage.costUsd)
    }

    @Test
    fun usage_topLevelCost_parsedAsUsd() {
        val data = """
            {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}],"cost":0.0042}
        """.trimIndent()
        val dto = json.decodeFromString(ChatResponseDto.serializer(), data)
        val usage = dto.usageOrCost()!!
        assertEquals(0.0042, usage.costUsd!!, 1e-9)
        assertNull(usage.inputTokens)
    }

    @Test
    fun usage_absent_returnsNull() {
        val data = """{"choices":[{"index":0,"delta":{"content":"ok"}}]}"""
        val dto = json.decodeFromString(ChatResponseDto.serializer(), data)
        assertNull(dto.usageOrCost())
    }

    @Test
    fun request_streamOptions_serializedForIncludeUsage() {
        val request = ChatRequestDto(
            model = "m",
            messages = listOf(ChatMessageDto(role = "user", content = JsonPrimitive("hi"))),
            stream = true,
            streamOptions = StreamOptionsDto(includeUsage = true),
        )
        val encoded = Json.encodeToString(ChatRequestDto.serializer(), request)
        org.junit.Assert.assertTrue("encoded=$encoded", encoded.contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun request_probeWithoutStream_hasNoStreamOptions() {
        val request = ChatRequestDto(
            model = "m",
            messages = listOf(ChatMessageDto(role = "user", content = JsonPrimitive("hi"))),
            stream = false,
        )
        val encoded = Json.encodeToString(ChatRequestDto.serializer(), request)
        // explicitNulls=false в ProviderJson не причастен: тут строгий Json,
        // null-поле не сериализуется вовсе.
        assertNotNull(encoded)
        assertEquals(false, encoded.contains("stream_options"))
    }
}
