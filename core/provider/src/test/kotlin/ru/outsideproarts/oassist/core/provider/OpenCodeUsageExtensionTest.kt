package ru.outsideproarts.oassist.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.BillingSource
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicMessageDeltaEvent
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicMessageStartEvent
import ru.outsideproarts.oassist.core.provider.anthropic.anthropicUsageOrNull
import ru.outsideproarts.oassist.core.provider.gemini.GeminiStreamChunkDto
import ru.outsideproarts.oassist.core.provider.gemini.toProviderUsage
import ru.outsideproarts.oassist.core.provider.openai.ChatResponseDto
import ru.outsideproarts.oassist.core.provider.openai.ResponsesStreamEventDto
import ru.outsideproarts.oassist.core.provider.openai.toProviderUsage
import ru.outsideproarts.oassist.core.provider.openai.usageOrCost

/**
 * Расширенная статистика OpenCode (`usage.cost` + `usage.opencode`) на всех
 * четырёх проводных протоколах и новые токены (reasoning/кэш-запись/tool-use).
 * Формат: https://opencode.ai/v2/docs/console/api/inference#cost
 */
class OpenCodeUsageExtensionTest {

    private val json = ProviderJson

    @Test
    fun completions_usageCostAndOpenCode_parsed() {
        val data = """
            {"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,
            "prompt_tokens_details":{"cached_tokens":8000},
            "completion_tokens_details":{"reasoning_tokens":120},
            "cost":0.00412,
            "opencode":{"billing_source":"balance","list_cost":0.00412,"estimated":false,
            "allowance":0.0031,
            "tokens":{"input":1200,"output":300,"reasoning":120,"cache_read":8000,"cache_write_5m":10,"cache_write_1h":5}}}}
        """.trimIndent()
        val dto = json.decodeFromString(ChatResponseDto.serializer(), data)
        val usage = dto.usageOrCost()!!
        assertEquals(0.00412, usage.costUsd!!, 1e-9)
        assertEquals(0.00412, usage.listCostUsd!!, 1e-9)
        assertEquals(0.0031, usage.allowanceUsd!!, 1e-9)
        assertEquals(BillingSource.BALANCE, usage.billingSource)
        assertEquals(false, usage.costEstimated)
        assertEquals(120L, usage.reasoningTokens)
        assertEquals(15L, usage.cacheWriteTokens)
        assertEquals(8000L, usage.cachedTokens)
    }

    @Test
    fun completions_legacyTopLevelCost_fallbackWhenUsageCostAbsent() {
        val data = """
            {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15},"cost":0.002}
        """.trimIndent()
        val dto = json.decodeFromString(ChatResponseDto.serializer(), data)
        assertEquals(0.002, dto.usageOrCost()!!.costUsd!!, 1e-9)
    }

    @Test
    fun completions_openCodeTokens_fillMissingNativeFields() {
        // Прокси без нативных токенов: добираем из билленых OpenCode.
        val data = """
            {"choices":[],"usage":{"cost":0.01,"opencode":{"billing_source":"go","tokens":{"input":7,"output":3,"reasoning":2,"cache_read":4}}}}
        """.trimIndent()
        val usage = json.decodeFromString(ChatResponseDto.serializer(), data).usageOrCost()!!
        assertEquals(7L, usage.inputTokens)
        assertEquals(3L, usage.outputTokens)
        assertEquals(10L, usage.totalTokens)
        assertEquals(2L, usage.reasoningTokens)
        assertEquals(4L, usage.cachedTokens)
        assertEquals(BillingSource.GO, usage.billingSource)
    }

    @Test
    fun responses_completedEvent_carriesCostAndReasoning() {
        val data = """
            {"type":"response.completed","response":{"id":"r1","status":"completed",
            "usage":{"input_tokens":100,"output_tokens":50,"total_tokens":150,
            "input_tokens_details":{"cached_tokens":30,"cache_write_tokens":12},
            "output_tokens_details":{"reasoning_tokens":20},
            "cost":0.0075,
            "opencode":{"billing_source":"go-plus","list_cost":0.008,"allowance":0.0075}}}}
        """.trimIndent()
        val event = json.decodeFromString(ResponsesStreamEventDto.serializer(), data)
        val usage = event.response!!.usage!!.toProviderUsage()!!
        assertEquals(20L, usage.reasoningTokens)
        assertEquals(12L, usage.cacheWriteTokens)
        assertEquals(30L, usage.cachedTokens)
        assertEquals(0.0075, usage.costUsd!!, 1e-9)
        assertEquals(0.008, usage.listCostUsd!!, 1e-9)
        assertEquals(0.0075, usage.allowanceUsd!!, 1e-9)
        assertEquals(BillingSource.GO_PLUS, usage.billingSource)
    }

    @Test
    fun anthropic_startAndDelta_deltaWinsWithoutDoubleCount() {
        val start = json.decodeFromString(
            AnthropicMessageStartEvent.serializer(),
            """{"type":"message_start","message":{"usage":{"input_tokens":25,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":1}}}""",
        )
        // message_delta у OpenCode несёт полную картину (input повторён + cost/opencode).
        val delta = json.decodeFromString(
            AnthropicMessageDeltaEvent.serializer(),
            """{"type":"message_delta","usage":{"input_tokens":25,"output_tokens":15,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"cost":0.0041,"opencode":{"billing_source":"balance","list_cost":0.0045,"tokens":{"reasoning":6}}}}""",        )
        val usage = anthropicUsageOrNull(start.message?.usage, delta.usage)!!
        // input не задвоен, total восстановлен с учётом кэша: 25+15+10+100.
        assertEquals(25L, usage.inputTokens)
        assertEquals(15L, usage.outputTokens)
        assertEquals(150L, usage.totalTokens)
        assertEquals(10L, usage.cacheWriteTokens)
        assertEquals(100L, usage.cachedTokens)
        assertEquals(0.0041, usage.costUsd!!, 1e-9)
        assertEquals(0.0045, usage.listCostUsd!!, 1e-9)
        assertEquals(6L, usage.reasoningTokens)
        assertEquals(BillingSource.BALANCE, usage.billingSource)
    }

    @Test
    fun anthropic_nativeOnly_cacheCreationMapsToCacheWrite() {
        val start = json.decodeFromString(
            AnthropicMessageStartEvent.serializer(),
            """{"type":"message_start","message":{"usage":{"input_tokens":50,"cache_creation_input_tokens":7,"cache_read_input_tokens":3,"output_tokens":1}}}""",
        )
        val delta = json.decodeFromString(
            AnthropicMessageDeltaEvent.serializer(),
            """{"type":"message_delta","usage":{"output_tokens":9}}""",
        )
        val usage = anthropicUsageOrNull(start.message?.usage, delta.usage)!!
        assertEquals(7L, usage.cacheWriteTokens)
        assertEquals(3L, usage.cachedTokens)
        assertEquals(9L, usage.outputTokens)
        // Полный вход = input + запись кэша + чтение кэша.
        assertEquals(69L, usage.totalTokens)
        assertNull(usage.costUsd)
    }

    @Test
    fun anthropic_cacheCreationTtlBreakdown_sumsWhenTotalAbsent() {
        val delta = json.decodeFromString(
            AnthropicMessageDeltaEvent.serializer(),
            """{"type":"message_delta","usage":{"input_tokens":1,"output_tokens":1,"cache_creation":{"ephemeral_5m_input_tokens":4,"ephemeral_1h_input_tokens":6}}}""",
        )
        val usage = anthropicUsageOrNull(null, delta.usage)!!
        assertEquals(10L, usage.cacheWriteTokens)
    }

    @Test
    fun gemini_usageMetadata_carriesCostAndToolTokens() {
        val data = """
            {"candidates":[],"usageMetadata":{"promptTokenCount":89,"candidatesTokenCount":12,"totalTokenCount":210,
            "cachedContentTokenCount":40,"thoughtsTokenCount":60,"toolUsePromptTokenCount":49,
            "cost":0.0021,
            "opencode":{"billing_source":"free","list_cost":0.0021,"tokens":{"input":89,"output":72,"reasoning":60,"cache_read":40}}}}
        """.trimIndent()
        val usage = json.decodeFromString(GeminiStreamChunkDto.serializer(), data).usageMetadata!!.toProviderUsage()!!
        assertEquals(49L, usage.toolTokens)
        assertEquals(60L, usage.reasoningTokens)
        // thinking входит в output (как биллит Gemini).
        assertEquals(72L, usage.outputTokens)
        assertEquals(210L, usage.totalTokens)
        assertEquals(0.0021, usage.costUsd!!, 1e-9)
        assertEquals(BillingSource.FREE, usage.billingSource)
    }

    @Test
    fun gemini_toolUseCountedInProviderTotal_notInInputOutput() {
        val usage = json.decodeFromString(
            GeminiStreamChunkDto.serializer(),
            """{"candidates":[],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"toolUsePromptTokenCount":3,"totalTokenCount":18}}""",
        ).usageMetadata!!.toProviderUsage()!!
        assertEquals(10L, usage.inputTokens)
        assertEquals(5L, usage.outputTokens)
        assertEquals(18L, usage.totalTokens)
        assertEquals(3L, usage.toolTokens)
    }

    @Test
    fun empty_withOpenCodeOnlyCost_isNotEmpty() {
        val data = """{"choices":[],"usage":{"cost":0.0}}""".trimIndent()
        val usage = json.decodeFromString(ChatResponseDto.serializer(), data).usageOrCost()!!
        assertEquals(0.0, usage.costUsd!!, 1e-9)
        assertTrue(!usage.isEmpty)
    }
}
