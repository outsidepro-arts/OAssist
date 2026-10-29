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
            val client = ModelClient.create(profile(ModelProtocol.ANTHROPIC_MESSAGES), emptySecrets, engine)
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
            val client = ModelClient.create(profile(null), emptySecrets, engine)
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
            val client = ModelClient.create(profile(null), emptySecrets, engine)
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
            val client = ModelClient.create(
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
    fun fetchModels_noneCatalog_sendsNoRequest() = runTest {
        var requests = 0
        val engine = http {
            requests++
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val client = ModelClient.create(
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
            val client = ModelClient.create(profile(null), emptySecrets, engine)
            assertEquals(emptySet<ModelCapability>(), client.probeCapabilities("gemini-2.5-flash"))
            assertEquals(listOf("/models/gemini-2.5-flash:generateContent"), paths)
        } finally {
            engine.close()
        }
    }
}
