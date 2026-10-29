package ru.outsideproarts.oassist.core.provider.opencode

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient
import ru.outsideproarts.oassist.core.provider.gemini.GeminiClient

/**
 * Роутинг агрегатора OpenCode: одна база, разные проводные протоколы по модели.
 * Стрим (SSE) MockEngine не умеет — уровень маршрутизации проверяем на probe
 * (обычный HTTP) и списке моделей; чистая логика — в [ProtocolResolutionTest].
 */
class OpenCodeRoutingTest {

    private val secrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = "zen-key".toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private val zenProfile = ProviderProfile(
        id = "p1",
        name = "Zen",
        preset = ProviderPreset.OPENCODE,
        baseUrl = "https://opencode.ai/zen/v1",
        apiKeyRef = "provider.p1.apiKey",
    )

    private fun mockClient(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient {
        val engine = MockEngine { request -> handler(request) }
        return HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(ProviderJson) }
        }
    }

    @Test
    fun urls_appendToAggregatorBaseAsIs() {
        assertEquals(
            "https://opencode.ai/zen/v1/models/gemini-3.5-flash:streamGenerateContent",
            GeminiClient.generateUrl("https://opencode.ai/zen/v1", "gemini-3.5-flash"),
        )
        assertEquals(
            "https://opencode.ai/zen/v1/models/gemini-3.5-flash:generateContent",
            GeminiClient.contentUrl("https://opencode.ai/zen/v1", "gemini-3.5-flash"),
        )
        assertEquals(
            "https://opencode.ai/zen/v1/messages",
            AnthropicClient.messagesUrl("https://opencode.ai/zen/v1"),
        )
    }

    @Test
    fun fetchModels_usesOpenAiList_withZenHeaders() = runTest {
        var path = ""
        val captured = mutableMapOf<String, String>()
        val client = ModelClient.create(zenProfile, secrets, mockClient { request ->
            path = request.url.encodedPath
            request.headers.entries()
                .filter { it.key.lowercase().startsWith("x-opencode") || it.key.lowercase() == "authorization" }
                .forEach { captured[it.key.lowercase()] = it.value.single() }
            respond("""{"data":[{"id":"claude-opus-4-8"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        })

        val models = client.fetchModels()

        assertEquals("/zen/v1/models", path)
        assertEquals(listOf("claude-opus-4-8"), models.map { it.id })
        assertEquals("Bearer zen-key", captured["authorization"])
        assertTrue(captured.containsKey("x-opencode-session"))
        assertTrue(captured.containsKey("x-opencode-request"))
    }

    @Test
    fun probe_routesByResolvedProtocol() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val client = ModelClient.create(zenProfile, secrets, mockClient { request ->
            calls += request.url.encodedPath to request.headers.entries()
                .associate { it.key.lowercase() to it.value.single() }
            respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        })

        client.probeCapabilities("claude-opus-4-8")
        client.probeCapabilities("gemini-3.5-flash")
        client.probeCapabilities("gpt-5.5")
        client.probeCapabilities("glm-5.3")

        assertEquals("/zen/v1/messages", calls[0].first)
        assertEquals("zen-key", calls[0].second["x-api-key"])
        assertEquals("2023-06-01", calls[0].second["anthropic-version"])

        assertEquals("/zen/v1/models/gemini-3.5-flash:generateContent", calls[1].first)
        assertEquals("zen-key", calls[1].second["x-goog-api-key"])

        assertEquals("/zen/v1/responses", calls[2].first)
        assertEquals("Bearer zen-key", calls[2].second["authorization"])

        assertEquals("/zen/v1/chat/completions", calls[3].first)
        assertEquals("Bearer zen-key", calls[3].second["authorization"])

        calls.forEach { (_, headers) ->
            assertTrue(headers.containsKey("x-opencode-session"))
            assertTrue(headers.containsKey("x-opencode-request"))
        }
    }

    @Test
    fun probe_manualOverrideChangesRoute() = runTest {
        var path = ""
        val client = ModelClient.create(
            zenProfile,
            secrets,
            mockClient { request ->
                path = request.url.encodedPath
                respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
            },
            protocolOverride = { ModelProtocol.GEMINI_GENERATE },
        )

        client.probeCapabilities("claude-opus-4-8")

        assertEquals("/zen/v1/models/claude-opus-4-8:generateContent", path)
    }

    private val unsupportedBody =
        """{"type":"error","error":{"type":"ModelProtocolUnsupported","message":"Model does not support this protocol."}}"""

    @Test
    fun probe_fallsBackOnUnsupportedProtocol_andLearnsRoute() = runTest {
        val paths = mutableListOf<String>()
        val client = ModelClient.create(
            zenProfile,
            secrets,
            mockClient { request ->
                paths += request.url.encodedPath
                if (request.url.encodedPath.endsWith("/chat/completions")) {
                    respond(unsupportedBody, status = HttpStatusCode.BadRequest, headers = headersOf("Content-Type", "application/json"))
                } else {
                    respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
                }
            },
        )

        // qwen3.7-max: эвристика → completions, Zen сигналит «не тот протокол» → messages.
        val caps = client.probeCapabilities("qwen3.7-max")

        assertEquals(setOf(ru.outsideproarts.oassist.core.model.ModelCapability.TOOLS), caps)
        assertEquals(listOf("/zen/v1/chat/completions", "/zen/v1/messages"), paths)

        // Выученный маршрут используется сразу, без повторного400.
        paths.clear()
        client.probeCapabilities("qwen3.7-max")
        assertEquals(listOf("/zen/v1/messages"), paths)
    }

    @Test
    fun probe_allUnsupported_throwsLastClassifiedError() = runTest {
        var attempts = 0
        val client = ModelClient.create(
            zenProfile,
            secrets,
            mockClient {
                attempts += 1
                respond(unsupportedBody, status = HttpStatusCode.BadRequest, headers = headersOf("Content-Type", "application/json"))
            },
        )

        val error = runCatching { client.probeCapabilities("jev-1.13") }.exceptionOrNull()

        assertTrue("expected failure, got $error", error != null)
        assertTrue("not classified: $error", ru.outsideproarts.oassist.core.provider.isProtocolUnsupported(error!!))
        assertEquals(3, attempts)
    }

    @Test
    fun probe_notUnsupportedError_propagatesImmediately() = runTest {
        var attempts = 0
        val client = ModelClient.create(
            zenProfile,
            secrets,
            mockClient {
                attempts += 1
                respond("""{"type":"error","error":{"type":"AuthError","message":"Missing API key."}}""", status = HttpStatusCode.Unauthorized, headers = headersOf("Content-Type", "application/json"))
            },
        )

        val error = runCatching { client.probeCapabilities("glm-5.3") }.exceptionOrNull()

        assertTrue(error != null)
        assertFalse("auth error must not trigger fallback", ru.outsideproarts.oassist.core.provider.isProtocolUnsupported(error!!))
        assertEquals(1, attempts)
    }
}
