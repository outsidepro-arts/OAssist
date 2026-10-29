package ru.outsideproarts.oassist.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.ProviderAuth
import ru.outsideproarts.oassist.core.provider.modelsCatalogUrl

class AnthropicClientTest {

    private val emptySecrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = "test-key".toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun client(engine: MockEngine, baseUrl: String): AnthropicClient {
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
            expectSuccess = true
        }
        val profile = ProviderProfile(
            id = "p1",
            name = "Anthropic",
            preset = ProviderPreset.CUSTOM,
            baseUrl = baseUrl,
        )
        return kotlinx.coroutines.runBlocking {
            AnthropicClient.fromProfile(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                profile,
                emptySecrets,
                http,
                overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
            )
        }
    }

    @Test
    fun fetchModels_v1Base_sendsHeaders_andLists() = runTest {
        var path = ""
        var apiKey: String? = null
        var version: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            apiKey = request.headers[AnthropicClient.API_KEY_HEADER]
            version = request.headers[AnthropicClient.VERSION_HEADER]
            respond(
                content = """{"data":[{"type":"model","id":"claude-opus-4-6","display_name":"Claude Opus 4.6"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val models = client(engine, "https://api.anthropic.com/v1").fetchModels()
        assertEquals("/v1/models", path)
        assertEquals("test-key", apiKey)
        assertEquals(AnthropicClient.ANTHROPIC_VERSION, version)
        assertEquals(listOf("claude-opus-4-6"), models.map { it.id })
        assertEquals("Claude Opus 4.6", models.single().label)
    }

    @Test
    fun fetchModels_v1Base_doesNotDoubleV1() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = """{"data":[{"id":"m1"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        client(engine, "https://api.anthropic.com/v1").fetchModels()
        assertEquals("/v1/models", path)
    }

    @Test
    fun urlHelpers_normalizeTrailingSlash() {
        assertEquals("https://h.test/v1/models", modelsCatalogUrl("https://h.test/v1/"))
        assertEquals("https://h.test/v1/messages", AnthropicClient.messagesUrl("https://h.test/v1/"))
    }

    @Test
    fun probe_200_yieldsTools() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"type":"message","content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val caps = client(engine, "https://api.anthropic.com").probeCapabilities("claude-opus-4-6")
        assertEquals(setOf(ru.outsideproarts.oassist.core.model.ModelCapability.TOOLS), caps)
    }

    @Test
    fun probe_400_yieldsEmpty() = runTest {
        val engine = MockEngine {
            respond("tools not accepted", status = HttpStatusCode.BadRequest)
        }
        val caps = client(engine, "https://api.anthropic.com").probeCapabilities("old-model")
        assertEquals(emptySet<ru.outsideproarts.oassist.core.model.ModelCapability>(), caps)
    }
}
