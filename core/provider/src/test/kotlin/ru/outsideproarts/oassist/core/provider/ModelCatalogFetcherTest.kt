package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AuthScheme
import ru.outsideproarts.oassist.core.model.ModelFetchScheme
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset

/**
 * Единый каталог моделей: путь `{base}/models` как есть (версия входит в baseUrl),
 * lenient-парсер обоих форматов ответа; неизвестный формат — явная ошибка.
 */
class ModelCatalogFetcherTest {

    private fun endpoint(baseUrl: String): EndpointConfig = EndpointConfig(
        protocol = ModelProtocol.OPENAI_COMPLETIONS,
        baseUrl = baseUrl,
        auth = ProviderAuth(),
    )

    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) { expectSuccess = true }

    @Test
    fun modelsCatalogUrl_appendsModelsAsIs() {
        assertEquals("https://h.test/models", modelsCatalogUrl("https://h.test/"))
        assertEquals("https://h.test/v1/models", modelsCatalogUrl("https://h.test/v1"))
        assertEquals("https://h.test/v1beta/models", modelsCatalogUrl("https://h.test/v1beta/"))
    }

    @Test
    fun fetch_parsesOpenAiShape() = runTest {
        val engine = http {
            respond("""{"object":"list","data":[{"id":"m1"},{"id":"m2"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val models = fetchModelCatalog(endpoint("https://h.test/v1"), engine)
            assertEquals(listOf("m1", "m2"), models.map { it.id })
            assertEquals(null, models.first().label)
        } finally {
            engine.close()
        }
    }

    @Test
    fun fetch_parsesAnthropicShapeWithLabels() = runTest {
        val engine = http {
            respond("""{"data":[{"type":"model","id":"claude-opus-4-6","display_name":"Claude Opus 4.6"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val models = fetchModelCatalog(endpoint("https://h.test/v1"), engine)
            assertEquals(listOf("claude-opus-4-6"), models.map { it.id })
            assertEquals("Claude Opus 4.6", models.single().label)
        } finally {
            engine.close()
        }
    }

    @Test
    fun fetch_parsesGeminiShape_stripsModelsPrefix() = runTest {
        val engine = http {
            respond("""{"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash"},{"name":"models/"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val models = fetchModelCatalog(endpoint("https://h.test/v1beta"), engine)
            assertEquals(listOf("gemini-2.0-flash"), models.map { it.id })
            assertEquals("Gemini 2.0 Flash", models.single().label)
        } finally {
            engine.close()
        }
    }

    @Test
    fun fetch_unrecognizedFormat_fails() = runTest {
        val engine = http {
            respond("""{"something":1}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            val result = runCatching { fetchModelCatalog(endpoint("https://h.test"), engine) }
            assertTrue("got: $result", result.isFailure)
            assertTrue("got: ${result.exceptionOrNull()}", result.exceptionOrNull() is ProviderError.Protocol)
        } finally {
            engine.close()
        }
    }

    @Test
    fun fetch_notJsonObject_fails() = runTest {
        val engine = http { respond("not-json-{", status = HttpStatusCode.OK) }
        try {
            val result = runCatching { fetchModelCatalog(endpoint("https://h.test"), engine) }
            assertTrue("got: $result", result.isFailure)
            assertTrue("got: ${result.exceptionOrNull()}", result.exceptionOrNull() is ProviderError.Protocol)
        } finally {
            engine.close()
        }
    }

    @Test
    fun fetch_sendsVersionHeaderAndPaginationParams() = runTest {
        var version: String? = null
        var query = ""
        val engine = http {
            version = it.headers["anthropic-version"]
            query = it.url.encodedQuery.orEmpty()
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        try {
            fetchModelCatalog(endpoint("https://h.test/v1"), engine)
            assertEquals("2023-06-01", version)
            assertTrue("query: $query", query.contains("pageSize=100"))
            assertTrue("query: $query", query.contains("limit=1000"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun flavorDefaults_areExplicit() {
        // Дефолты вкуса — конкретные значения, без «Авто».
        assertEquals(ModelFetchScheme.MODELS, ProviderPreset.CUSTOM.defaultFetchScheme())
        assertEquals(ModelFetchScheme.MODELS, ProviderPreset.OPENCODE.defaultFetchScheme())
        assertEquals(AuthScheme.NATIVE, ProviderPreset.CUSTOM.defaultAuthScheme())
        assertEquals(AuthScheme.NATIVE, ProviderPreset.OPENCODE.defaultAuthScheme())
    }
}
