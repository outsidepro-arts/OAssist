package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.AuthScheme
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import java.util.Base64

/**
 * Размещение учётных данных по схеме авторизации: нативная всегда key-based
 * (Bearer / x-api-key / x-goog-api-key), Basic — пара логин/пароль,
 * явные схемы перекрывают нативную для любого протокола.
 */
class AuthSchemeTest {

    private fun fetchWith(
        protocol: ModelProtocol,
        authScheme: AuthScheme,
        auth: ProviderAuth,
        apiKeyHeaderName: String? = null,
    ): Map<String, String> {
        var captured: Map<String, String> = emptyMap()
        val engine = MockEngine { request: HttpRequestData ->
            captured = request.headers.entries().associate { it.key.lowercase() to it.value.single() }
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val http = HttpClient(engine) { expectSuccess = true }
        try {
            kotlinx.coroutines.runBlocking {
                fetchModelCatalog(
                    EndpointConfig(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
                        protocol = protocol,
                        baseUrl = "https://h.test/v1",
                        auth = auth,
                        authScheme = authScheme,
                        apiKeyHeaderName = apiKeyHeaderName,
                    ),
                    http,
                )
            }
        } finally {
            http.close()
        }
        return captured
    }

    private fun basic64(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    @Test
    fun native_openai_sendsBearer() {
        val headers = fetchWith(ModelProtocol.OPENAI_COMPLETIONS, AuthScheme.NATIVE, ProviderAuth(apiKey = "k".toCharArray()))
        assertEquals("Bearer k", headers["authorization"])
    }

    @Test
    fun native_anthropic_sendsApiKeyHeader() {
        val headers = fetchWith(ModelProtocol.ANTHROPIC_MESSAGES, AuthScheme.NATIVE, ProviderAuth(apiKey = "k".toCharArray()))
        assertEquals("k", headers["x-api-key"])
        assertNull(headers["authorization"])
    }

    @Test
    fun native_gemini_sendsGoogApiKey() {
        val headers = fetchWith(ModelProtocol.GEMINI_GENERATE, AuthScheme.NATIVE, ProviderAuth(apiKey = "k".toCharArray()))
        assertEquals("k", headers["x-goog-api-key"])
        assertNull(headers["authorization"])
    }

    @Test
    fun bearer_overridesNativeForAnyProtocol() {
        val headers = fetchWith(ModelProtocol.ANTHROPIC_MESSAGES, AuthScheme.BEARER, ProviderAuth(apiKey = "k".toCharArray()))
        assertEquals("Bearer k", headers["authorization"])
        assertNull(headers["x-api-key"])
    }

    @Test
    fun apiKeyHeader_usesGivenName_andNativeNameByDefault() {
        val custom = fetchWith(
            ModelProtocol.OPENAI_COMPLETIONS,
            AuthScheme.API_KEY_HEADER,
            ProviderAuth(apiKey = "k".toCharArray()),
            apiKeyHeaderName = "x-custom-key",
        )
        assertEquals("k", custom["x-custom-key"])

        val byNative = fetchWith(
            ModelProtocol.GEMINI_GENERATE,
            AuthScheme.API_KEY_HEADER,
            ProviderAuth(apiKey = "k".toCharArray()),
        )
        assertEquals("k", byNative["x-goog-api-key"])
    }

    @Test
    fun basic_sendsBase64Pair() {
        val headers = fetchWith(
            ModelProtocol.OPENAI_COMPLETIONS,
            AuthScheme.BASIC,
            ProviderAuth(basicUser = "user", basicPassword = "pass".toCharArray()),
        )
        assertEquals(basic64("user", "pass"), headers["authorization"])
    }

    @Test
    fun none_sendsNoAuthHeaders() {
        val headers = fetchWith(ModelProtocol.OPENAI_COMPLETIONS, AuthScheme.NONE, ProviderAuth(apiKey = "k".toCharArray()))
        assertNull(headers["authorization"])
        assertNull(headers["x-api-key"])
        assertNull(headers["x-goog-api-key"])
    }

    @Test
    fun create_resolvesBasicPairFromSecretStore() = runTest {
        val secrets = object : SecretStore {
            override suspend fun put(alias: String, plaintext: CharArray) = Unit
            override suspend fun get(alias: String): CharArray? = when (alias) {
                "provider.p.basicUser" -> "user".toCharArray()
                "provider.p.basicPassword" -> "pass".toCharArray()
                else -> null
            }
            override suspend fun contains(alias: String): Boolean = true
            override suspend fun delete(alias: String) = Unit
            override suspend fun clearAll() = Unit
            override suspend fun aliases(): List<String> = emptyList()
        }
        var captured: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            captured = request.headers.entries().associate { it.key.lowercase() to it.value.single() }
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val http = HttpClient(engine) { expectSuccess = true }
        try {
            val profile = ProviderProfile(
                id = "p",
                name = "P",
                preset = ProviderPreset.CUSTOM,
                baseUrl = "https://h.test",
                authScheme = AuthScheme.BASIC,
                basicUserRef = "provider.p.basicUser",
                basicPasswordRef = "provider.p.basicPassword",
            )
            ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, profile, secrets, http).fetchModels()
            assertEquals(basic64("user", "pass"), captured["authorization"])
        } finally {
            http.close()
        }
    }
}
