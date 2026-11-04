package ru.outsideproarts.oassist.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderError

class GeminiPromptBlockIntegrationTest {
    private class SseEngine(config: MockEngineConfig) : MockEngine(config) {
        override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> = super.supportedCapabilities + SSECapability
        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val response = super.execute(data)
            val body = response.body as? ByteReadChannel ?: return response
            val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(
                data, response.statusCode, response.headers, body, data.body, response.callContext,
            ) ?: return response
            return HttpResponseData(response.statusCode, response.requestTime, response.headers,
                response.version, adapted, response.callContext)
        }
    }

    @Test
    fun blockedPrompt_propagatesWithoutRetryOrAssistantAnswer() = runTest {
        var requests = 0
        val engine = SseEngine(MockEngineConfig().apply { requestHandlers.add {
            requests++
            respond("data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\",\"blockReasonMessage\":\"ModelProtocolUnsupported 503\"}}\n\n",
                HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        } })
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(SSE)
            expectSuccess = true
        }
        val secrets = object : SecretStore {
            override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) = Unit
            override suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?> = null to null
            override suspend fun put(alias: String, plaintext: CharArray) = Unit
            override suspend fun get(alias: String): CharArray? = null
            override suspend fun contains(alias: String) = false
            override suspend fun delete(alias: String) = Unit
            override suspend fun clearAll() = Unit
            override suspend fun aliases(): List<String> = emptyList()
        }
        try {
            val client = ModelClient.create(Diagnostics, ProviderProfile(id = "p", name = "P",
                preset = ProviderPreset.CUSTOM, baseUrl = "https://h.test"), secrets, http)
            val chunks = mutableListOf<ChatChunk>()
            var saved = false
            val error = runCatching {
                AgentLoop(client).stream("gemini-test", null, emptyList(), "hi", onTurnMessages = { saved = true })
                    .collect { chunks += it }
            }.exceptionOrNull()
            assertEquals("SAFETY", (ProviderError.from(error!!) as ProviderError.PromptBlocked).reason)
            assertEquals(1, requests)
            assertTrue(chunks.isEmpty())
            assertTrue(!saved)
        } finally { http.close() }
    }
}
