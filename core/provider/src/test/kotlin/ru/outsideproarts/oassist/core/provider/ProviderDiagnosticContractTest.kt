package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.net.WireJson
import ru.outsideproarts.oassist.core.model.diagnostics.*

class ProviderDiagnosticContractTest {
    @Test fun oversizedProviderErrorIsOmittedRatherThanTruncatedThroughCredential() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val recorder = object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events += event }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        }
        val token = "credential-across-boundary"
        val body = "x".repeat(65530) + token
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.Unauthorized) }) { expectSuccess = true }
        try {
            val error = runCatching { http.get("https://example.com") }.exceptionOrNull()!!
            recordProviderHttpError(recorder, "parent", error, listOf(token))
            assertEquals(DiagnosticKind.PROVIDER_ERROR, events.single().kind)
            assertEquals(null, events.single().content)
        } finally { http.close() }
    }
    @Test fun allProtocolsRecordEffectiveWireRequest() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events += event }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        val secretStore = object : SecretStore {
            override suspend fun put(alias: String, plaintext: CharArray) = Unit
            override suspend fun get(alias: String): CharArray? = null
            override suspend fun contains(alias: String): Boolean = false
            override suspend fun delete(alias: String) = Unit
            override suspend fun clearAll() = Unit
            override suspend fun aliases(): List<String> = emptyList()
        }
        try {
            for (protocol in ModelProtocol.entries) {
                events.clear()
                var wire = ""
                val http = HttpClient(MockEngine { request ->
                    wire = (request.body as TextContent).text
                    respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }) {
                    expectSuccess = true
                    install(ContentNegotiation) { json(WireJson) }
                }
                try {
                    val profile = ProviderProfile(id = "p", name = "P", preset = ProviderPreset.CUSTOM,
                        baseUrl = "https://example.com/v1", protocol = protocol,
                        extraBody = """{"diagnostic_probe":42}""")
                    val client = ModelClient.create(Diagnostics, profile, secretStore, http)
                    runCatching { client.probeCapabilities("test-model") }
                    val snapshot = events.single { it.kind == DiagnosticKind.PROVIDER_REQUEST }
                    assertTrue("request was not sent for $protocol", wire.isNotEmpty())
                    assertEquals("wire mismatch for $protocol",
                        Json.parseToJsonElement(wire), Json.parseToJsonElement(snapshot.content!!))
                    assertTrue(snapshot.content!!.contains("diagnostic_probe"))
                    assertTrue(snapshot.parentOperationId != null)
                } finally { http.close() }
            }
        } finally { Diagnostics.swapForTest(previous) }
    }
    @Test fun catalogErrorIncludesServerBodyAndKnownSecretIsPassedForCleaning() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) {
                var content = event.content
                secrets.filter { it.isNotEmpty() }.forEach { secret -> content = content?.replace(secret, "[redacted]") }
                events += event.copy(content = content)
            }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        val http = HttpClient(MockEngine { respond("""{"code":"invalid_key","detail":"secret-key"}""", HttpStatusCode.Unauthorized) }) {
            expectSuccess = true
        }
        try {
            val endpoint = EndpointConfig(Diagnostics, ModelProtocol.OPENAI_COMPLETIONS,
                "https://example.com/v1", ProviderAuth(apiKey = "secret-key".toCharArray()))
            runCatching { fetchModelCatalog(endpoint, http) }
            val error = events.single { it.kind == DiagnosticKind.OPERATION_ERROR }
            assertEquals("401", error.attributes["status"])
            assertTrue(error.content!!.contains("invalid_key"))
            assertFalse(error.content!!.contains("secret-key"))
        } finally {
            http.close()
            Diagnostics.swapForTest(previous)
        }
    }
    @Test fun sseSecretSplitAcrossFramesIsRemovedBeforeRecorder() {
        val events = mutableListOf<DiagnosticEvent>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events += event }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        try {
            val endpoint = EndpointConfig(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, ModelProtocol.OPENAI_COMPLETIONS,
                "https://example.com", ProviderAuth(apiKey = "abcdefgh".toCharArray()))
            val capture = SseDiagnosticCapture(endpoint, DiagnosticContext("parent", secrets = listOf("tool-secret")))
            capture.add("""{"delta":{"content":"abcd"}}""")
            capture.add("""{"delta":{"content":"efgh"}}""")
            capture.add("""{"delta":{"content":"tool-"}}""")
            capture.add("""{"delta":{"content":"secret"}}""")
            capture.flush()
            val event = events.single()
            assertEquals("parent", event.parentOperationId)
            assertFalse(event.content!!.contains("abcd"))
            assertFalse(event.content!!.contains("efgh"))
            assertFalse(event.content!!.contains("tool-"))
            assertFalse(event.content!!.contains("secret"))
        } finally {
            Diagnostics.swapForTest(previous)
        }
    }
    @Test fun catalogRecordsResponseAndPassesCredentialsForRedaction() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val capturedSecrets = mutableListOf<String>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) {
                events += event
                capturedSecrets += secrets
            }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        val http = HttpClient(MockEngine { respond("""{"data":[{"id":"example"}]}""", HttpStatusCode.OK) })
        try {
            val endpoint = EndpointConfig(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, ModelProtocol.OPENAI_COMPLETIONS,
                "https://example.com/v1?token=hidden", ProviderAuth(apiKey = "secret-key".toCharArray()))
            assertEquals("example", fetchModelCatalog(endpoint, http).single().id)
            val start = events.single { it.kind == DiagnosticKind.OPERATION_START }
            val catalog = events.single { it.kind == DiagnosticKind.PROVIDER_CATALOG }
            assertEquals(start.operationId, catalog.operationId)
            assertEquals("200", catalog.attributes["status"])
            assertFalse(start.content!!.contains("hidden"))
            assertTrue(capturedSecrets.contains("secret-key"))
        } finally {
            http.close()
            Diagnostics.swapForTest(previous)
        }
    }
}
