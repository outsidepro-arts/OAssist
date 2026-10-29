package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient

/**
 * Статистика на уровне стримов клиентов: кумулятивный usage Gemini не
 * суммируется по чанкам, лимиты из HTTP-заголовков доходят до финального
 * Finish, message_delta у Anthropic перекрывает message_start без задвоения.
 */
class StreamUsageTest {

    private val secrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = "test-key".toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun http(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(SSE)
        expectSuccess = true
    }

    /**
     * MockEngine не декларирует SSECapability и не вызывает ResponseAdapter
     * (как это делает OkHttp) — добавляем и то и другое, чтобы sse {} получал
     * настоящий SSESession из тела ответа.
     */
    private class SseMockEngine(config: MockEngineConfig) : MockEngine(config) {
        override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> =
            super.supportedCapabilities + SSECapability

        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val response = super.execute(data)
            val body = response.body
            if (body !is ByteReadChannel) return response
            val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(
                data,
                response.statusCode,
                response.headers,
                body,
                data.body,
                response.callContext,
            ) ?: return response
            return HttpResponseData(
                response.statusCode,
                response.requestTime,
                response.headers,
                response.version,
                adapted,
                response.callContext,
            )
        }
    }

    private fun sseEngine(handler: MockRequestHandler): SseMockEngine =
        SseMockEngine(MockEngineConfig().apply { requestHandlers.add(handler) })

    private fun profile(baseUrl: String) = ProviderProfile(
        id = "p1",
        name = "P",
        preset = ProviderPreset.CUSTOM,
        baseUrl = baseUrl,
    )

    private val messages = listOf(ChatMessage(role = ChatRole.USER, content = "hi"))

    private fun sseBody(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" }

    private fun namedSseBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" }

    @Test
    fun gemini_cumulativeUsageMetadata_lastSnapshotWins() = runTest {
        val engine = sseEngine {
            respond(
                content = sseBody(
                    """{"candidates":[{"content":{"parts":[{"text":"a"}]}}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":1,"totalTokenCount":8}}""",
                    """{"candidates":[{"content":{"parts":[{"text":"b"}]}}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2,"totalTokenCount":9}}""",
                    """{"candidates":[],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2,"totalTokenCount":9}}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("text/event-stream")),
            )
        }
        val client = GeminiClient.fromProfile(
            profile("https://generativelanguage.googleapis.com"),
            secrets,
            http(engine),
            overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
        )
        val finish = client.chatCompletionStream("gemini-2.0-flash", messages)
            .toList().filterIsInstance<ChatChunk.Finish>().last()
        val usage = finish.usage!!
        // usageMetadata кумулятивен: 9, а не 8+9.
        assertEquals(9L, usage.totalTokens)
        assertEquals(7L, usage.inputTokens)
        assertEquals(2L, usage.outputTokens)
    }

    @Test
    fun gemini_rateLimitHeaders_attachedToFinalUsage() = runTest {
        val engine = sseEngine {
            respond(
                content = sseBody("""{"candidates":[],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "Content-Type" to listOf("text/event-stream"),
                    "x-ratelimit-remaining-requests" to listOf("41"),
                    "x-ratelimit-limit-requests" to listOf("60"),
                ),
            )
        }
        val client = GeminiClient.fromProfile(
            profile("https://generativelanguage.googleapis.com"),
            secrets,
            http(engine),
            overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
        )
        val finish = client.chatCompletionStream("gemini-2.0-flash", messages)
            .toList().filterIsInstance<ChatChunk.Finish>().last()
        val limits = finish.usage!!.rateLimits!!
        assertEquals(41L, limits.requests!!.remaining)
        assertEquals(60L, limits.requests!!.limit)
    }

    @Test
    fun anthropic_deltaUsageWinsOverStart_withoutDoubleCount() = runTest {
        val engine = sseEngine {
            respond(
                content = namedSseBody(
                    "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":25,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":1}}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":25,"output_tokens":15,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"cost":0.0041,"opencode":{"billing_source":"balance"}}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("text/event-stream")),
            )
        }
        val client = AnthropicClient.fromProfile(
            profile("https://api.anthropic.com"),
            secrets,
            http(engine),
            overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
        )
        val finish = client.chatCompletionStream("claude-opus-4-6", messages)
            .toList().filterIsInstance<ChatChunk.Finish>().last()
        val usage = finish.usage!!
        assertEquals(25L, usage.inputTokens)
        assertEquals(15L, usage.outputTokens)
        assertEquals(150L, usage.totalTokens)
        assertEquals(10L, usage.cacheWriteTokens)
        assertEquals(100L, usage.cachedTokens)
        assertEquals(0.0041, usage.costUsd!!, 1e-9)
    }

    @Test
    fun openAi_stream_finalUsageChunkWithOpenCode_parsedWithLimits() = runTest {
        val engine = sseEngine {
            respond(
                content = sseBody(
                    """{"id":"c1","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                    """{"id":"c1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"cost":0.002,"opencode":{"billing_source":"go","allowance":0.0019,"tokens":{"reasoning":5}}}}""",
                    "[DONE]",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "Content-Type" to listOf("text/event-stream"),
                    "x-ratelimit-remaining-tokens" to listOf("1500"),
                    "x-ratelimit-limit-tokens" to listOf("2000"),
                ),
            )
        }
        val client = OpenAiCompatibleClient.fromProfile(
            profile("https://opencode.ai/zen/go/v1"),
            secrets,
            http(engine),
            overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
        )
        val finish = client.chatCompletionStream("kimi-k3", messages)
            .toList().filterIsInstance<ChatChunk.Finish>().last()
        val usage = finish.usage!!
        assertEquals(120L, usage.totalTokens)
        assertEquals(0.002, usage.costUsd!!, 1e-9)
        assertEquals(0.0019, usage.allowanceUsd!!, 1e-9)
        assertEquals(5L, usage.reasoningTokens)
        assertNotNull(usage.rateLimits)
        assertEquals(1500L, usage.rateLimits!!.tokens!!.remaining)
    }
}
