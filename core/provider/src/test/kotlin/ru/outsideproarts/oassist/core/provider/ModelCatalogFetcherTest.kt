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
import kotlinx.coroutines.launch
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

    @Test
    fun geminiPages_preserveOpaqueCursorAndPurpose() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val token = "a +/&?=ю"
        val engine = http { request ->
            requests += request
            assertEquals("100", request.url.parameters["pageSize"])
            assertEquals(null, request.url.parameters["limit"])
            if (requests.size == 1) respond("""{"models":[{"name":"models/a","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"$token"}""")
            else {
                assertEquals(token, request.url.parameters["pageToken"])
                respond("""{"models":[{"name":"models/b","supportedGenerationMethods":["generateContent"]},{"name":"models/unknown"}]}""")
            }
        }
        try {
            val models = fetchModelCatalog(endpoint("https://h.test/v1beta", ModelProtocol.GEMINI_GENERATE), engine)
            assertEquals(listOf("a", "b", "unknown"), models.map { it.id })
            assertEquals(listOf(false, true, true), models.map { it.isChatCompatible })
            assertEquals(listOf("embedContent"), models.first().supportedGenerationMethods)
            assertEquals(2, requests.size)
        } finally { engine.close() }
    }

    @Test
    fun dataPages_followResponsePaginationEvenForAggregator() = runTest {
        var calls = 0
        val token = "opaque +/&?=cursor"
        val engine = http { request ->
            calls++
            assertEquals(null, request.url.parameters["pageSize"])
            assertEquals(null, request.url.parameters["limit"])
            if (calls == 1) respond("""{"data":[{"id":"a","type":"classification"}],"has_more":true,"last_id":"$token"}""")
            else {
                assertEquals(token, request.url.parameters["after_id"])
                respond("""{"data":[{"id":"a","type":"classification"},{"id":"b","name":"Chat","type":"chat"}],"has_more":false}""")
            }
        }
        try {
            val models = fetchModelCatalog(endpoint("https://h.test/v1"), engine)
            assertEquals(listOf("a", "b"), models.map { it.id })
            assertEquals("classification", models.first().purpose)
            assertEquals(listOf(false, true), models.map { it.isChatCompatible })
            assertEquals("Chat", models.last().label)
            assertEquals(2, calls)
        } finally { engine.close() }
    }

    @Test
    fun paginationRejectsRepeatedMissingAndConflictingCursorsOrModels() = runTest {
        val cases = listOf(
            listOf("""{"data":[{"id":"a"}],"has_more":true}"""),
            listOf("""{"data":[],"has_more":true,"last_id":"a"}""", """{"data":[],"has_more":true,"last_id":"a"}"""),
            listOf("""{"models":[],"nextPageToken":"a"}""", """{"models":[],"nextPageToken":"b"}""", """{"models":[],"nextPageToken":"a"}"""),
            listOf("""{"data":[{"id":"a","type":"chat"}],"has_more":true,"last_id":"x"}""", """{"data":[{"id":"a","type":"classification"}]}"""),
            listOf("""{"models":[{"name":"models/"}]}"""),
            listOf("""{"data":[],"has_more":true,"last_id":"x"}""", """{"models":[]}"""),
        )
        for (pages in cases) {
            var calls = 0
            val engine = http { respond(pages[calls++]) }
            try {
                val error = runCatching { fetchModelCatalog(endpoint("https://h.test"), engine) }.exceptionOrNull()
                assertTrue("$pages: $error", error is ProviderError.Protocol)
                assertEquals(pages.size, calls)
            } finally { engine.close() }
        }
    }

    @Test
    fun secondPageFailureOrCancellationNeverReturnsPartialCatalog() = runTest {
        for (cancel in listOf(false, true)) {
            var calls = 0
            val engine = http {
                if (++calls == 1) respond("""{"models":[{"name":"models/a"}],"nextPageToken":"next"}""")
                else if (cancel) throw kotlinx.coroutines.CancellationException("cancel page two")
                else respond("unavailable", HttpStatusCode.ServiceUnavailable)
            }
            try {
                val error = runCatching { fetchModelCatalog(endpoint("https://h.test"), engine) }.exceptionOrNull()
                assertTrue(error != null)
                assertEquals(cancel, error is kotlinx.coroutines.CancellationException)
                assertEquals(2, calls)
            } finally { engine.close() }
        }
    }

    @Test
    fun endlessUniqueCursorsFailAtPageLimit() = runTest {
        var calls = 0
        val engine = http { respond("""{"models":[],"nextPageToken":"${++calls}"}""") }
        try {
            val error = runCatching { fetchModelCatalog(endpoint("https://h.test"), engine) }.exceptionOrNull()
            assertTrue(error is ProviderError.Protocol)
            assertEquals(100, calls)
        } finally { engine.close() }
    }

    @Test
    fun oversizedCatalogFailsInsteadOfReturningTruncatedList() = runTest {
        val body = (0..100_000).joinToString(prefix = "{\"data\":[", postfix = "]}") { "{\"id\":\"$it\"}" }
        val engine = http { respond(body) }
        try {
            assertTrue(runCatching { fetchModelCatalog(endpoint("https://h.test"), engine) }.exceptionOrNull() is ProviderError.Protocol)
        } finally { engine.close() }
    }

    @Test
    fun cancellingPendingSecondPageCancelsWholeFetch() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val engine = http {
            if (++calls == 1) respond("""{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}""")
            else {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        try {
            var completed = false
            val job = launch {
                fetchModelCatalog(endpoint("https://h.test", ModelProtocol.ANTHROPIC_MESSAGES), engine)
                completed = true
            }
            entered.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(!completed)
            assertEquals(2, calls)
        } finally { engine.close() }
    }

    @Test
    fun mixedCatalogDoesNotInferPurposeFromNames() = runTest {
        val engine = http { respond("""{"data":[{"id":"innocent","type":"classification"},{"id":"typesafe/jev"},{"id":"embedding-in-name","type":"chat"},{"id":"unknown","type":"future"},{"id":"anthropic","type":"model"},{"id":"image","type":"image"}]}""") }
        try {
            val models = fetchModelCatalog(endpoint("https://h.test"), engine)
            assertEquals(listOf(false, true, true, true, true, false), models.map { it.isChatCompatible })
        } finally { engine.close() }
    }

    private fun endpoint(
        baseUrl: String,
        protocol: ModelProtocol = ModelProtocol.OPENAI_COMPLETIONS,
    ): EndpointConfig = EndpointConfig(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics,
        protocol = protocol,
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
            respond("""{"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
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
            fetchModelCatalog(endpoint("https://h.test/v1", ModelProtocol.ANTHROPIC_MESSAGES), engine)
            assertEquals("2023-06-01", version)
            assertTrue("query: $query", !query.contains("pageSize"))
            assertTrue("query: $query", query.contains("limit=1000"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun fetch_geminiCatalog_sendsOnlySupportedPaginationParams() = runTest {
        val engine = http { request ->
            assertEquals("/v1beta/models", request.url.encodedPath)
            assertEquals(setOf("pageSize"), request.url.parameters.names())
            assertEquals(listOf("100"), request.url.parameters.getAll("pageSize"))
            respond(
                """{"models":[{"name":"models/gemini-test"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        try {
            val models = fetchModelCatalog(
                endpoint("https://h.test/v1beta", ModelProtocol.GEMINI_GENERATE),
                engine,
            )
            assertEquals(listOf("gemini-test"), models.map { it.id })
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
