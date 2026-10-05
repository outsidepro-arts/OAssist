package ru.outsideproarts.oassist.core.provider.openai

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
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.ProviderAuth

class OpenAiProbeTest {

    private val emptySecrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = "test-key".toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun client(engine: MockEngine): OpenAiCompatibleClient {
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
            expectSuccess = true
        }
        val profile = ProviderProfile(
            id = "p1",
            name = "Test",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.example.com/v1",
        )
        return kotlinx.coroutines.runBlocking {
            OpenAiCompatibleClient.fromProfile(
                profile,
                emptySecrets,
                http,
                overrides = ProviderAuth(bearerToken = "test-key".toCharArray()),
            )
        }
    }

    @Test
    fun probe_200_yieldsTools() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        assertEquals(setOf(ModelCapability.TOOLS), client(engine).probeCapabilities("m1"))
    }

    @Test
    fun probe_400_yieldsEmpty() = runTest {
        val engine = MockEngine {
            respond("tools not accepted", status = HttpStatusCode.BadRequest)
        }
        assertEquals(emptySet<ModelCapability>(), client(engine).probeCapabilities("m1"))
    }

    @Test
    fun basicAuth_sendsBasicHeaderInsteadOfBearer() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers["Authorization"]
            respond(
                content = """{"object":"list","data":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
            expectSuccess = true
        }
        val profile = ProviderProfile(
            id = "p1",
            name = "Zen",
            kind = ProviderKind.OPENCODE_ZEN,
            baseUrl = "https://zen.example.com/v1",
        )
        val zen = kotlinx.coroutines.runBlocking {
            OpenAiCompatibleClient.fromProfile(
                profile,
                emptySecrets,
                http,
                overrides = ProviderAuth(
                    bearerToken = "bearer".toCharArray(),
                    basicUser = "u".toCharArray(),
                    basicPassword = "p".toCharArray(),
                ),
            )
        }
        zen.fetchModels()
        val expected = "Basic " + kotlin.io.encoding.Base64.encode("u:p".toByteArray())
        assertEquals(expected, authorization)
    }
}
