package ru.outsideproarts.oassist.core.provider.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ProviderJson

class GeminiUsageTest {

    private val json = ProviderJson

    @Test
    fun usageMetadata_finalChunkWithEmptyCandidates_parses() {
        val data = """
            {"candidates":[],"usageMetadata":{"promptTokenCount":89,"candidatesTokenCount":12,"totalTokenCount":101,"cachedContentTokenCount":40}}
        """.trimIndent()
        val dto = json.decodeFromString(GeminiStreamChunkDto.serializer(), data)
        assertEquals(emptyList<GeminiCandidateDto>(), dto.candidates)
        val usage = dto.usageMetadata!!.toProviderUsage()!!
        assertEquals(89L, usage.inputTokens)
        assertEquals(12L, usage.outputTokens)
        assertEquals(101L, usage.totalTokens)
        assertEquals(40L, usage.cachedTokens)
    }

    @Test
    fun usageMetadata_thoughtsCountedAsOutput() {
        val metadata = GeminiUsageMetadataDto(
            promptTokenCount = 10,
            candidatesTokenCount = 5,
            thoughtsTokenCount = 7,
            totalTokenCount = 22,
        )
        val usage = metadata.toProviderUsage()!!
        assertEquals(12L, usage.outputTokens)
        assertEquals(22L, usage.totalTokens)
    }

    @Test
    fun usageMetadata_absent_returnsNull() {
        val dto = json.decodeFromString(
            GeminiStreamChunkDto.serializer(),
            """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        assertNull(dto.usageMetadata)
        assertNull(dto.usageMetadata?.toProviderUsage())
    }

    @Test
    fun usageMetadata_onChunkWithCandidates_stillReadable() {
        val data = """
            {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1,"totalTokenCount":4}}
        """.trimIndent()
        val dto = json.decodeFromString(GeminiStreamChunkDto.serializer(), data)
        assertNotNull(dto.candidates)
        assertEquals(4L, dto.usageMetadata!!.toProviderUsage()!!.totalTokens)
    }
}
