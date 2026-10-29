package ru.outsideproarts.oassist.core.provider.gemini

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

class GeminiClientTest {

    private val emptySecrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = "test-key".toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun client(engine: MockEngine, baseUrl: String): GeminiClient {
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
            expectSuccess = true
        }
        val profile = ProviderProfile(
            id = "p1",
            name = "Gemini",
            preset = ProviderPreset.CUSTOM,
            baseUrl = baseUrl,
        )
        return kotlinx.coroutines.runBlocking {
            GeminiClient.fromProfile(
                profile,
                emptySecrets,
                http,
                overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
            )
        }
    }

    @Test
    fun fetchModels_v1betaBase_sendsHeader_andLists() = runTest {
        var path = ""
        var apiKey: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            apiKey = request.headers[GeminiClient.API_KEY_HEADER]
            respond(
                content = """{"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash","supportedGenerationMethods":["generateContent"]}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val models = client(engine, "https://generativelanguage.googleapis.com/v1beta").fetchModels()
        assertEquals("/v1beta/models", path)
        assertEquals("test-key", apiKey)
        assertEquals(listOf("gemini-2.0-flash"), models.map { it.id })
        assertEquals("Gemini 2.0 Flash", models.single().label)
    }

    @Test
    fun fetchModels_v1betaBase_doesNotDouble() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(
                content = """{"models":[{"name":"models/m1"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        client(engine, "https://generativelanguage.googleapis.com/v1beta").fetchModels()
        assertEquals("/v1beta/models", path)
    }

    @Test
    fun probe_200_yieldsTools() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"STOP"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val caps = client(engine, "https://generativelanguage.googleapis.com").probeCapabilities("gemini-2.0-flash")
        assertEquals(setOf(ru.outsideproarts.oassist.core.model.ModelCapability.TOOLS), caps)
    }

    @Test
    fun probe_400_yieldsEmpty() = runTest {
        val engine = MockEngine {
            respond("tools not accepted", status = HttpStatusCode.BadRequest)
        }
        val caps = client(engine, "https://generativelanguage.googleapis.com").probeCapabilities("old-model")
        assertEquals(emptySet<ru.outsideproarts.oassist.core.model.ModelCapability>(), caps)
    }

    @Test
    fun urlHelpers_normalize() {
        assertEquals("https://h.test/v1beta/models", modelsCatalogUrl("https://h.test/v1beta/"))
        assertEquals(
            "https://h.test/models/gemini-2.0-flash:streamGenerateContent",
            GeminiClient.generateUrl("https://h.test", "gemini-2.0-flash"),
        )
        assertEquals(
            "https://h.test/v1beta/models/gemini-2.0-flash:streamGenerateContent",
            GeminiClient.generateUrl("https://h.test/v1beta/", "models/gemini-2.0-flash"),
        )
    }
}
