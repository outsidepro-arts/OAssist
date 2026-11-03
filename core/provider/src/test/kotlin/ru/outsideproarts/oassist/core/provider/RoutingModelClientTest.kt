package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelFetchScheme
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile

/**
 * Универсальный роутинг проводного протокола: фикс протокола профиля — одна
 * попытка, авто-резолв — фолбэк по 404/405 (нет такого эндпоинта протокола),
 * per-model override побеждает фикс профиля.
 */
class RoutingModelClientTest {

    private val emptySecrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = null
        override suspend fun contains(alias: String): Boolean = false
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private val anthropicProbeOk =
        """{"type":"message","content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}"""

    private fun profile(protocol: ModelProtocol?, baseUrl: String = "https://h.test") = ProviderProfile(
        id = "p",
        name = "P",
        preset = ProviderPreset.CUSTOM,
        baseUrl = baseUrl,
        protocol = protocol,
    )

    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) {
            expectSuccess = true
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(ProviderJson) }
            install(io.ktor.client.plugins.sse.SSE)
        }

    @Test
    fun fixedProtocol_failsWithoutPermutation_on404() = runTest {
        var requests = 0
        val engine = http {
            requests++
            respond("missing", status = HttpStatusCode.NotFound)
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, profile(ModelProtocol.ANTHROPIC_MESSAGES), emptySecrets, engine)
            val result = runCatching { client.probeCapabilities("m") }
            assertTrue("expected failure, got $result", result.isFailure)
            assertEquals(1, requests)
        } finally {
            engine.close()
        }
    }

    @Test
    fun autoProtocol_fallsBackOn404_andLearnsWorkingRoute() = runTest {
        var requests = 0
        var failNext = true
        val engine = http {
            requests++
            if (failNext) {
                failNext = false
                respond("missing", status = HttpStatusCode.NotFound)
            } else {
                respond(anthropicProbeOk, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
            }
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, profile(null), emptySecrets, engine)
            // custom-model не распознаётся эвристикой → completions, потом фолбэк на messages.
            assertEquals(setOf(ModelCapability.TOOLS), client.probeCapabilities("custom-model"))
            assertEquals(2, requests)

            // Выученный маршрут используется сразу, без повторного фолбэка.
            requests = 0
            assertEquals(setOf(ModelCapability.TOOLS), client.probeCapabilities("custom-model"))
            assertEquals(1, requests)
        } finally {
            engine.close()
        }
    }

    @Test
    fun autoProtocol_fallsBackOn405() = runTest {
        var requests = 0
        var failNext = true
        val engine = http {
            requests++
            if (failNext) {
                failNext = false
                respond("wrong verb", status = HttpStatusCode.MethodNotAllowed)
            } else {
                respond(anthropicProbeOk, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
            }
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, profile(null), emptySecrets, engine)
            assertEquals(setOf(ModelCapability.TOOLS), client.probeCapabilities("custom-model"))
            assertEquals(2, requests)
        } finally {
            engine.close()
        }
    }

    @Test
    fun fixedProtocol_perModelOverride_wins() = runTest {
        val paths = mutableListOf<String>()
        val engine = http {
            paths += it.url.encodedPath
            respond("tools not accepted", status = HttpStatusCode.BadRequest)
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                profile(ModelProtocol.ANTHROPIC_MESSAGES),
                emptySecrets,
                engine,
                protocolOverride = { ModelProtocol.OPENAI_COMPLETIONS },
            )
            assertEquals(emptySet<ModelCapability>(), client.probeCapabilities("m"))
            assertEquals(listOf("/chat/completions"), paths)
        } finally {
            engine.close()
        }
    }

    @Test
    fun googlePreset_withoutProtocol_fetchesCatalogWithNativeAuth() = runTest {
        val secrets = object : SecretStore by emptySecrets {
            override suspend fun get(alias: String): CharArray? =
                if (alias == "google-key") "test-google-key".toCharArray() else null
        }
        val engine = http { request ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models", request.url.toString().substringBefore('?'))
            assertEquals(setOf("pageSize"), request.url.parameters.names())
            assertEquals("100", request.url.parameters["pageSize"])
            assertEquals("test-google-key", request.headers["x-goog-api-key"])
            assertNull(request.headers["Authorization"])
            respond(
                """{"models":[{"name":"models/gemini-test","displayName":"Gemini Test"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                profile(null, ProviderPreset.GOOGLE.defaultBaseUrl()!!).copy(
                    preset = ProviderPreset.GOOGLE,
                    apiKeyRef = "google-key",
                ),
                secrets,
                engine,
            )
            assertEquals(listOf("gemini-test"), client.fetchModels().map { it.id })
        } finally {
            engine.close()
        }
    }

    @Test
    fun googlePreset_withoutProtocol_usesGeminiForUnrecognizedModelId() = runTest {
        val paths = mutableListOf<String>()
        val engine = http { request ->
            paths += request.url.encodedPath
            assertEquals("test-google-key", request.headers["x-goog-api-key"])
            assertNull(request.headers["Authorization"])
            respond("tools not accepted", status = HttpStatusCode.BadRequest)
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                profile(null, ProviderPreset.GOOGLE.defaultBaseUrl()!!).copy(preset = ProviderPreset.GOOGLE),
                emptySecrets,
                engine,
                overrides = ProviderAuth(apiKey = "test-google-key".toCharArray()),
            )
            assertEquals(emptySet<ModelCapability>(), client.probeCapabilities("opaque-model"))
            assertEquals(listOf("/v1beta/models/opaque-model:generateContent"), paths)
        } finally {
            engine.close()
        }
    }

    @Test
    fun googlePreset_explicitProtocol_takesPrecedence() = runTest {
        val engine = http { request ->
            assertEquals("/v1beta/openai/models", request.url.encodedPath)
            assertEquals("Bearer test-google-key", request.headers["Authorization"])
            assertNull(request.headers["x-goog-api-key"])
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                profile(ModelProtocol.OPENAI_COMPLETIONS, "https://generativelanguage.googleapis.com/v1beta/openai")
                    .copy(preset = ProviderPreset.GOOGLE),
                emptySecrets,
                engine,
                overrides = ProviderAuth(apiKey = "test-google-key".toCharArray()),
            )
            assertTrue(client.fetchModels().isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun autoPresets_fetchCatalogWithBearerAuth() = runTest {
        for (preset in listOf(ProviderPreset.CUSTOM, ProviderPreset.OPENCODE)) {
            val engine = http { request ->
                assertEquals("/v1/models", request.url.encodedPath)
                assertEquals("Bearer test-key", request.headers["Authorization"])
                assertNull(request.headers["x-goog-api-key"])
                respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
            }
            try {
                val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                    profile(null, "https://h.test/v1").copy(preset = preset),
                    emptySecrets,
                    engine,
                    overrides = ProviderAuth(apiKey = "test-key".toCharArray()),
                )
                assertTrue(client.fetchModels().isEmpty())
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun fetchModels_noneCatalog_sendsNoRequest() = runTest {
        var requests = 0
        val engine = http {
            requests++
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                profile(null).copy(fetchScheme = ModelFetchScheme.NONE),
                emptySecrets,
                engine,
            )
            assertEquals(emptyList<Any>(), client.fetchModels())
            assertEquals(0, requests)
        } finally {
            engine.close()
        }
    }

    @Test
    fun autoProtocol_heuristicRoutesByModelId() = runTest {
        val paths = mutableListOf<String>()
        val engine = http {
            paths += it.url.encodedPath
            respond("tools not accepted", status = HttpStatusCode.BadRequest)
        }
        try {
            val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, profile(null), emptySecrets, engine)
            assertEquals(emptySet<ModelCapability>(), client.probeCapabilities("gemini-2.5-flash"))
            assertEquals(listOf("/models/gemini-2.5-flash:generateContent"), paths)
        } finally {
            engine.close()
        }
    }
}
