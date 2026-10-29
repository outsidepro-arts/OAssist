package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelFetchScheme
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.net.WireJson

/**
 * Кастомизации «Своей настройки»: `extraBody` мержится поверх сериализованного
 * тела запроса (дубль ключа — дополнение побеждает), `catalogPath` меняет путь
 * каталога моделей.
 */
class ExtraBodyMergeTest {

    private val secrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = null
        override suspend fun contains(alias: String): Boolean = false
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun capturingClient(capture: (String) -> Unit): HttpClient = HttpClient(
        MockEngine { request ->
            capture((request.body as TextContent).text)
            respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        },
    ) {
        expectSuccess = true
        install(ContentNegotiation) { json(WireJson) }
    }

    private fun profile(extraBody: String?, catalogPath: String? = null) = ProviderProfile(
        id = "p",
        name = "P",
        preset = ProviderPreset.CUSTOM,
        baseUrl = "https://h.test/v1",
        protocol = ModelProtocol.OPENAI_COMPLETIONS,
        extraBody = extraBody,
        catalogPath = catalogPath,
    )

    @Test
    fun extraBody_mergesKeys_andKeepsBaseBody() = runTest {
        var body = ""
        val http = capturingClient { body = it }
        try {
            val client = ModelClient.create(profile(extraBody = """{"user":"u1"}"""), secrets, http)
            client.probeCapabilities("m1")

            assertTrue("body: $body", body.contains("\"model\":\"m1\""))
            assertTrue("body: $body", body.contains("\"user\":\"u1\""))
        } finally {
            http.close()
        }
    }

    @Test
    fun extraBody_duplicateKeyWins() = runTest {
        var body = ""
        val http = capturingClient { body = it }
        try {
            val client = ModelClient.create(profile(extraBody = """{"model":"OVERRIDE"}"""), secrets, http)
            client.probeCapabilities("m1")

            assertTrue("body: $body", body.contains("\"model\":\"OVERRIDE\""))
        } finally {
            http.close()
        }
    }

    @Test
    fun withoutExtraBody_bodyIsUnchanged() = runTest {
        var body = ""
        val http = capturingClient { body = it }
        try {
            val client = ModelClient.create(profile(extraBody = null), secrets, http)
            client.probeCapabilities("m1")

            assertTrue("body: $body", body.contains("\"model\":\"m1\""))
        } finally {
            http.close()
        }
    }

    @Test
    fun catalogPath_movesCatalogRequest() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond("""{"data":[{"id":"m1"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(WireJson) }
        }
        try {
            val client = ModelClient.create(
                profile(extraBody = null, catalogPath = "catalog/").copy(fetchScheme = ModelFetchScheme.MODELS),
                secrets,
                http,
            )
            assertEquals(listOf("m1"), client.fetchModels().map { it.id })
            assertEquals("/v1/catalog", path)
        } finally {
            http.close()
        }
    }
}
