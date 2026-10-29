package ru.outsideproarts.oassist.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.diagnostics.DiagnosticStore
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.agent.InMemoryApprovalPolicy
import ru.outsideproarts.oassist.core.agent.ToolRegistry
import ru.outsideproarts.oassist.core.agent.mcp.McpClient
import ru.outsideproarts.oassist.core.agent.mcp.McpError
import ru.outsideproarts.oassist.core.agent.mcp.McpTool
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.net.WireJson
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.feature.settings.DiagnosticsScreen
import ru.outsideproarts.oassist.feature.settings.R

@RunWith(AndroidJUnit4::class)
class DiagnosticDeviceTest {
    private class SseMockEngine(config: MockEngineConfig) : MockEngine(config) {
        override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> =
            super.supportedCapabilities + SSECapability
        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val response = super.execute(data)
            val body = response.body
            if (body !is ByteReadChannel) return response
            val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(
                data, response.statusCode, response.headers, body, data.body, response.callContext,
            ) ?: return response
            return HttpResponseData(response.statusCode, response.requestTime, response.headers,
                response.version, adapted, response.callContext)
        }
    }
    @get:Rule val compose = createComposeRule()
    private lateinit var base: Context
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var store: DiagnosticStore
    private lateinit var preferences: String

    @Before fun prepare() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "diagnostic-device-${UUID.randomUUID()}").apply { check(mkdirs()) }
        preferences = "${directory.name}-diagnostics"
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${directory.name}-$name", mode)
        }
        store = DiagnosticStore(context)
    }

    @After fun cleanup() {
        store.close()
        require(directory.canonicalFile.toPath().startsWith(base.cacheDir.canonicalFile.toPath()))
        check(directory.deleteRecursively())
        base.deleteSharedPreferences(preferences)
    }

    @Test fun keystoreEncryptsContentAndExportRedactsCredentialsAndSeparatesMedia() {
        val privateText = "private-device-${UUID.randomUUID()}"
        val credential = "credential-${UUID.randomUUID()}"
        val image = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(image)
        store.record(DiagnosticEvent(
            DiagnosticKind.PROVIDER_REQUEST, "device-root",
            content = """{"prompt":"$privateText","echo":"$credential","image":"data:image/png;base64,$encoded"}""",
        ), listOf(credential))
        val withoutMedia = archive()
        assertTrue(store.health().toString(), store.health().available)
        assertEquals(0L, store.health().droppedEvents)
        val events = withoutMedia.getValue("events.jsonl").decodeToString()
        assertTrue(events.contains(privateText))
        assertFalse(events.contains(credential))
        assertFalse(events.contains("data:image/png;base64"))
        assertFalse(withoutMedia.keys.any { it.startsWith("media/") })
        val encrypted = File(context.noBackupFilesDir, "diagnostics").listFiles().orEmpty()
            .filter { it.extension == "event" || it.extension == "media" }
        assertTrue(encrypted.isNotEmpty())
        encrypted.forEach { file ->
            val raw = file.readBytes().decodeToString()
            assertFalse(raw.contains(privateText))
            assertFalse(raw.contains(credential))
        }
        val withMedia = archive(includeMedia = true)
        assertArrayEquals(image, withMedia.filterKeys { it.startsWith("media/") }.values.single())
    }

    @Test fun disableClearAndAuthoritativeConversationDeletionRejectLateContent() {
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "old", content = "old-private"))
        archive()
        store.setContentEnabled(false)
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_RESPONSE, "disabled", content = "disabled-private"))
        assertFalse(archive().getValue("events.jsonl").decodeToString().contains("disabled-private"))
        store.clear()
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_RESPONSE, "late", "old", content = "late-private"))
        store.setContentEnabled(true)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "saved", content = "saved-private"))
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_RESPONSE, "child", "saved", content = "child-private"))
        archive()
        store.removeConversation("saved-chat", setOf("saved"))
        val events = archive().getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("private"))
    }

    @Test fun mcpCredentialEchoIsRemovedFromArchivedResponseAndFollowingAgentRound() = runBlocking {
        val token = "mcp-credential-${UUID.randomUUID()}"
        var requests = 0
        val engine = MockEngine {
            requests++
            val body = when {
                requests <= 2 -> """{"jsonrpc":"2.0","id":1,"result":{}}"""
                requests == 3 -> """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"mcp-echo-marker $token"}]}}"""
                else -> """{"jsonrpc":"2.0","id":4,"error":{"code":-1,"message":"mcp-error-marker $token"}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(
                "Content-Type" to listOf("application/json"),
                McpClient.SESSION_HEADER to listOf("session"),
            ))
        }
        val http = HttpClient(engine)
        try {
            val client = McpClient(store, http, "https://mcp.example.com", mapOf("Authorization" to "Bearer $token"))
            val tool = McpTool("s", "server", "read", "mcp_s__read", null, JsonObject(emptyMap()), client)
            val policy = InMemoryApprovalPolicy().apply { setRemembered(tool.groupKey, ApprovalState.ALLOW) }
            var round = 0
            val model = object : ModelClient {
                override suspend fun fetchModels(): List<ModelInfo> = emptyList()
                override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>,
                    tools: List<ToolSchemaDto>, reasoningEnabled: Boolean): kotlinx.coroutines.flow.Flow<ChatChunk> {
                    round++
                    return if (round == 1) flowOf(ChatChunk.ToolCall("call", tool.schema.name, "{}"), ChatChunk.Finish("tool_calls"))
                    else flowOf(ChatChunk.Content("done"), ChatChunk.Finish("stop"))
                }
            }
            val registry = ToolRegistry(listOf(tool), diagnostics = store)
            AgentLoop(model, toolRegistry = registry, approvalPolicy = policy, diagnostics = store)
                .stream("model", null, emptyList(), "question").toList()
            try { client.callTool("read", JsonObject(emptyMap())); fail("expected MCP error") }
            catch (_: McpError) { }
            val events = archive().getValue("events.jsonl").decodeToString()
            assertTrue(events.lineSequence().any { it.contains("OPERATION_END") && it.contains("mcp-echo-marker") })
            assertTrue(events.lineSequence().any { it.contains("OPERATION_END") && it.contains("mcp-error-marker") })
            assertTrue(events.lineSequence().any { it.contains("AGENT_ROUND") && it.contains("mcp-echo-marker") })
            assertTrue(events.lineSequence().any { it.contains("TOOL_RESULT") && it.contains("mcp-echo-marker") })
            assertFalse(events.contains(token))
            assertTrue(events.contains("[REDACTED]"))
        } finally { http.close() }
    }

    @Test fun switchesHaveAccessibleStateAndExportRequiresConsent() {
        compose.setContent { MaterialTheme { DiagnosticsScreen(store, onBack = {}) } }
        val recording = context.getString(R.string.diagnostics_record)
        val media = context.getString(R.string.diagnostics_media)
        compose.onNodeWithText(recording).assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithText(media).assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithText(recording).performClick()
        compose.waitUntil { !store.health().contentEnabled }
        compose.onNodeWithText(recording).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.diagnostics_share)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostics_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostics_confirm_title)).assertDoesNotExist()
    }

    @Test fun mcpSecretIsRedactedAcrossRealProviderWireAndSseArchive() = runBlocking {
        val token = "mcp-secret-${UUID.randomUUID()}"
        val half = token.length / 2
        val wireBodies = mutableListOf<String>()
        var modelCalls = 0
        val config = MockEngineConfig().apply {
            requestHandlers.add { request ->
                if (request.url.host == "mcp.example.com") {
                    val body = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"tool-wire-marker $token"}]}}"""
                    respond(body, HttpStatusCode.OK, headersOf(
                        "Content-Type" to listOf("application/json"), McpClient.SESSION_HEADER to listOf("session")))
                } else {
                    var body = request.body
                    while (body is OutgoingContent.ContentWrapper) body = body.delegate()
                    wireBodies += (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    modelCalls++
                    val frames = if (modelCalls == 1) listOf(
                        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call","type":"function","function":{"name":"mcp_s__read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                        "[DONE]",
                    ) else listOf(
                        """{"choices":[{"index":0,"delta":{"content":"sse-echo-marker ${token.substring(0, half)}"}}]}""",
                        """{"choices":[{"index":0,"delta":{"content":"${token.substring(half)}"},"finish_reason":"stop"}]}""",
                        "[DONE]",
                    )
                    respond(frames.joinToString("") { "data: $it\n\n" }, HttpStatusCode.OK,
                        headersOf("Content-Type" to listOf("text/event-stream")))
                }
            }
        }
        val http = HttpClient(SseMockEngine(config)) {
            install(SSE)
            install(ContentNegotiation) { json(WireJson) }
        }
        try {
            val mcp = McpClient(store, http, "https://mcp.example.com/mcp", mapOf("Authorization" to "Bearer $token"))
            val tool = McpTool("s", "server", "read", "mcp_s__read", null, JsonObject(emptyMap()), mcp)
            val policy = InMemoryApprovalPolicy().apply { setRemembered(tool.groupKey, ApprovalState.ALLOW) }
            val secretStore = object : SecretStore {
                override suspend fun put(alias: String, plaintext: CharArray) = Unit
                override suspend fun get(alias: String): CharArray? = null
                override suspend fun contains(alias: String) = false
                override suspend fun delete(alias: String) = Unit
                override suspend fun clearAll() = Unit
                override suspend fun aliases(): List<String> = emptyList()
            }
            val profile = ProviderProfile("provider", "provider", ProviderPreset.CUSTOM,
                "https://model.example.com/v1", protocol = ModelProtocol.OPENAI_COMPLETIONS)
            val model = ModelClient.create(store, profile, secretStore, http)
            AgentLoop(model, toolRegistry = ToolRegistry(listOf(tool), diagnostics = store),
                approvalPolicy = policy, diagnostics = store).stream("model", null, emptyList(), "question").toList()
            assertEquals(2, modelCalls)
            assertTrue(wireBodies.last().contains("tool-wire-marker $token"))
            val events = archive().getValue("events.jsonl").decodeToString()
            assertTrue(events.lineSequence().any { it.contains("PROVIDER_REQUEST") && it.contains("tool-wire-marker") })
            assertTrue(events.lineSequence().any { it.contains("PROVIDER_RESPONSE") && it.contains("sse-echo-marker") })
            assertFalse(events.contains(token))
            assertFalse(events.contains(token.substring(0, half)))
            assertFalse(events.contains(token.substring(half)))
        } finally { http.close() }
    }

    @Test fun providerHttpErrorsReachArchiveWithScopedCredentialsRemoved() = runBlocking {
        val token = "scoped-credential-${UUID.randomUUID()}"
        var requests = 0
        val config = MockEngineConfig().apply {
            requestHandlers.add {
                requests++
                val marker = if (requests == 1) "stream-error-marker" else "probe-error-marker"
                respond("""{"error":"$marker $token"}""", HttpStatusCode.Unauthorized,
                    headersOf("Content-Type" to listOf("application/json")))
            }
        }
        val http = HttpClient(SseMockEngine(config)) {
            install(SSE)
            install(ContentNegotiation) { json(WireJson) }
            expectSuccess = true
        }
        try {
            val secretStore = object : SecretStore {
                override suspend fun put(alias: String, plaintext: CharArray) = Unit
                override suspend fun get(alias: String): CharArray? = null
                override suspend fun contains(alias: String) = false
                override suspend fun delete(alias: String) = Unit
                override suspend fun clearAll() = Unit
                override suspend fun aliases(): List<String> = emptyList()
            }
            val profile = ProviderProfile("provider", "provider", ProviderPreset.CUSTOM,
                "https://model.example.com/v1", protocol = ModelProtocol.OPENAI_COMPLETIONS)
            val model = ModelClient.create(store, profile, secretStore, http)
            withContext(DiagnosticContext("parent", secrets = listOf(token))) {
                assertTrue(runCatching { model.chatCompletionStream("model", emptyList()).toList() }.isFailure)
                assertTrue(runCatching { model.probeCapabilities("model") }.isFailure)
            }
            val events = archive().getValue("events.jsonl").decodeToString()
            val errorLines = events.lineSequence().filter { it.contains("PROVIDER_ERROR") }.toList()
            val debug = "errorEvents=${errorLines.size}, httpBodyEvents=${errorLines.count { it.contains("http_error_body") }}, omitted=${errorLines.count { it.contains("body_omitted") && it.contains("true") }}"
            assertTrue(debug, errorLines.any { it.contains("stream-error-marker") })
            assertTrue(debug, errorLines.any { it.contains("probe-error-marker") })
            assertFalse(events.contains(token))
        } finally { http.close() }
    }

    private fun archive(includeMedia: Boolean = false): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        store.export(output, sinceMillis = 0, includeMedia = includeMedia)
        return ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
    }
}
