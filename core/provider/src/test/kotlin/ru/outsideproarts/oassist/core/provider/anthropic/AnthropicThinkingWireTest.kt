package ru.outsideproarts.oassist.core.provider.anthropic

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.provider.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Real Ktor/OkHttp HTTP bodies and SSE, with transport redirected locally after policy selection. */
class AnthropicThinkingWireTest {
    private val user = listOf(ChatMessage(ChatRole.USER, "question"))
    private val adaptive = AnthropicThinkingCapabilities(adaptive = true, enabled = false)
    private val manual = AnthropicThinkingCapabilities(adaptive = false, enabled = true)

    @Test fun modesUseProviderMetadataWithoutModelNameGuesses() = runBlocking {
        for ((native, caps) in listOf(true to adaptive, true to manual, false to adaptive, false to manual, false to null)) {
            Fixture(native, caps).use { f ->
                f.client.chatCompletionStream("arbitrary-alias", user, emptyList(), true).toList()
                assertEquals(1, f.requests.size)
                val body = f.posts.single().body
                val thinking = body.getValue("thinking").jsonObject
                if (caps == adaptive) {
                    assertEquals("adaptive", thinking.getValue("type").jsonPrimitive.content)
                    assertFalse(thinking.containsKey("budget_tokens"))
                    assertEquals(native, thinking.containsKey("block_binding"))
                } else {
                    assertEquals("enabled", thinking.getValue("type").jsonPrimitive.content)
                    assertEquals(4096, thinking.getValue("budget_tokens").jsonPrimitive.int)
                    assertTrue(body.getValue("max_tokens").jsonPrimitive.int > 4096)
                }
                assertFalse(body.containsKey("output_config"))
                assertEquals(native && caps == adaptive, f.posts.single().beta?.contains(AnthropicClient.THINKING_BINDING_BETA) == true)
            }
        }
    }

    @Test fun nativeUnknownRetrievesOnceAndCachesOnlyWithinItsClient() = runBlocking {
        Fixture(metadata = """{"id":"resolved","capabilities":{"thinking":{"types":{"adaptive":{"supported":true},"enabled":{"supported":false}}}}}""").use { f ->
            repeat(2) { f.client.chatCompletionStream("alias", user, emptyList(), true).toList() }
            assertEquals(listOf("GET", "POST", "POST"), f.requests.map { it.method })
            assertEquals("/v1/models/alias", f.requests.first().path)
            assertTrue(f.posts.all { it.body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content == "adaptive" })
        }
        Fixture().use { fresh ->
            val error = runCatching { fresh.client.chatCompletionStream("alias", user, emptyList(), true).toList() }.exceptionOrNull()
            assertEquals(ProviderError.InvalidInput.Reason.THINKING_CAPABILITIES_UNKNOWN, (error as ProviderError.InvalidInput).reason)
            assertEquals(listOf("GET"), fresh.requests.map { it.method })
        }
    }

    @Test fun missingOrUnsupportedMetadataDoesNotSendNativeModelRequest() = runBlocking {
        for (caps in listOf(null, AnthropicThinkingCapabilities(false, false))) Fixture(caps = caps).use { f ->
            assertTrue(runCatching { f.client.chatCompletionStream("alias", user, emptyList(), true).toList() }.exceptionOrNull() is ProviderError.InvalidInput)
            assertTrue(f.posts.isEmpty())
            assertEquals(if (caps == null) 1 else 0, f.requests.size)
        }
        Fixture(getStatus = 401).use { f ->
            val error = runCatching { f.client.chatCompletionStream("alias", user, emptyList(), true).toList() }.exceptionOrNull()!!
            assertEquals(401, (ProviderError.from(error) as ProviderError.Http).statusCode)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun partialCatalogDoesNotMaskSuccessfullyRetrievedCapabilitiesOnContinuation() = runBlocking {
        Fixture(caps = AnthropicThinkingCapabilities(), afterFirstGetStatus = 503,
            metadata = """{"id":"alias","capabilities":{"thinking":{"types":{"adaptive":{"supported":true}}}}}""").use { f ->
            repeat(2) { f.client.chatCompletionStream("alias", user, emptyList(), true).toList() }
            assertEquals(listOf("GET", "POST", "POST"), f.requests.map { it.method })
        }
    }

    @Test fun falseFirstRequestOmitsThinkingEvenForKnownAlwaysOnModel() = runBlocking {
        Fixture(caps = adaptive.copy(disabled = false)).use { f ->
            f.client.chatCompletionStream("alias", user, emptyList(), false).toList()
            assertEquals(1, f.requests.size)
            assertFalse(f.posts.single().body.containsKey("thinking"))
            assertNull(f.posts.single().beta)
        }
    }

    @Test fun catalogMetadataSurvivesSerializationAndDoesNotReplaceUserFlagsOrRejectProxyShapes() = runBlocking {
        Fixture(metadata = """{"data":[{"id":"a","capabilities":{"thinking":{"types":{"adaptive":{"supported":true},"enabled":{"supported":false}}}}},{"id":"b","capabilities":["vision"]},{"id":"c","capabilities":{"thinking":{"types":{"adaptive":{"supported":"true"}}}}}]}""").use { f ->
            val models = f.client.fetchModels()
            assertEquals(adaptive, models[0].anthropicThinking)
            assertTrue(models[0].capabilities.isEmpty())
            assertNull(models[1].anthropicThinking)
            assertNull(models[2].anthropicThinking!!.adaptive)
            val edited = models[0].copy(capabilities = setOf(ModelCapability.REASONING))
            assertEquals(edited, Json.decodeFromString<ModelInfo>(Json.encodeToString(edited)))
            assertNull(Json.decodeFromString<ModelInfo>("""{"id":"legacy"}""").anthropicThinking)
        }
    }

    @Test fun actualNativeThinkingAllowsFalseContinuationButInvalidOrForeignSnapshotDoesNot() = runBlocking {
        val blocks = """[{"type":"thinking","thinking":"","signature":"opaque"},{"type":"text","text":"answer"}]"""
        for (native in listOf(true, false)) Fixture(native, adaptive).use { f ->
            val origin = JsonArray(listOf("anthropic_messages", f.base, "", "alias").map(::JsonPrimitive)).toString()
            val original = ChatMessage(ChatRole.ASSISTANT, "answer", reasoning = ReasoningBlock("", anthropicReplay = AnthropicReplay(origin, blocks)))
            for (message in listOf(original, original.copy(content = "edited"), original.copy(reasoning = ReasoningBlock("", signature = "legacy")),
                original.copy(reasoning = original.reasoning!!.copy(anthropicReplay = AnthropicReplay("foreign", blocks))))) {
                f.client.chatCompletionStream("alias", user + message + ChatMessage(ChatRole.USER, "next"), emptyList(), false).toList()
                val body = f.posts.last().body
                val expected = native && message == original
                assertEquals(expected, body.containsKey("thinking"))
                assertEquals(expected, body.toString().contains("opaque"))
            }
        }
    }

    @Test fun explicitThinkingAndEffortReplaceAutomaticObjectWithoutInjectedBudgetOrBeta() = runBlocking {
        val extra = Json.parseToJsonElement("""{"thinking":{"type":"adaptive","display":"omitted"},"output_config":{"effort":"low"},"max_tokens":1234}""").jsonObject
        Fixture(extra = extra).use { f ->
            f.client.chatCompletionStream("alias", user, emptyList(), true).toList()
            assertEquals(1, f.requests.size)
            assertEquals(extra["thinking"], f.posts.single().body["thinking"])
            assertEquals(extra["output_config"], f.posts.single().body["output_config"])
            assertEquals(extra["max_tokens"], f.posts.single().body["max_tokens"])
            assertNull(f.posts.single().beta)
        }
    }

    @Test fun malformedOpaqueProofDoesNotActivateThinkingOrCrash() = runBlocking {
        for (type in listOf("thinking", "redacted_thinking")) {
            for (invalid in listOf("{}", "[]", "123", "true", "null", "\"\"", "\"   \"")) {
                Fixture().use { f ->
                    val field = if (type == "thinking") "signature" else "data"
                    val blocks = """[{"type":"$type","$field":$invalid},{"type":"text","text":"answer"}]"""
                    val origin = JsonArray(listOf("anthropic_messages", f.base, "", "alias").map(::JsonPrimitive)).toString()
                    val message = ChatMessage(ChatRole.ASSISTANT, "answer", reasoning = ReasoningBlock("", anthropicReplay = AnthropicReplay(origin, blocks)))
                    f.client.chatCompletionStream("alias", user + message, emptyList(), false).toList()
                    assertEquals(listOf("POST"), f.requests.map { it.method })
                    assertFalse(f.posts.single().body.containsKey("thinking"))
                    assertFalse(f.posts.single().body.toString().contains(field))
                }
            }
        }
    }

    @Test fun actualExtraBodyModelControlsLookupConfigurationAndReplayOrigin() = runBlocking {
        val sse = "event: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"opaqueB\"}}\n\n" +
            "event: content_block_stop\ndata: {\"index\":0}\n\nevent: message_stop\ndata: {}\n\n"
        Fixture(extra = Json.parseToJsonElement("""{"model":"B"}""").jsonObject, sse = sse,
            capsById = { if (it == "A") manual else null },
            metadata = """{"id":"B","capabilities":{"thinking":{"types":{"adaptive":{"supported":true},"enabled":{"supported":false}}}}}""").use { f ->
            val chunks = f.client.chatCompletionStream("A", user, emptyList(), true).toList()
            assertEquals("/v1/models/B", f.requests.first().path)
            assertEquals(listOf("B"), f.lookups)
            val replay = chunks.filterIsInstance<ChatChunk.Reasoning>().mapNotNull { it.anthropicReplay }.single()
            assertEquals("B", Json.parseToJsonElement(replay.origin).jsonArray.last().jsonPrimitive.content)
            val saved = ChatMessage(ChatRole.ASSISTANT, "", reasoning = ReasoningBlock("", anthropicReplay = replay))
            f.client.chatCompletionStream("A", user + saved, emptyList(), false).toList()
            assertEquals(listOf("GET", "POST", "POST"), f.requests.map { it.method })
            assertTrue(f.posts.all { it.body["model"] == JsonPrimitive("B") && it.body["thinking"]!!.jsonObject["type"] == JsonPrimitive("adaptive") })
            assertTrue(f.posts.last().body.toString().contains("opaqueB"))
            val foreign = saved.copy(reasoning = saved.reasoning!!.copy(anthropicReplay = replay.copy(
                origin = JsonArray(listOf("anthropic_messages", f.base, "", "A").map(::JsonPrimitive)).toString())))
            f.client.chatCompletionStream("A", user + foreign, emptyList(), false).toList()
            assertFalse(f.posts.last().body.containsKey("thinking"))
            assertFalse(f.posts.last().body.toString().contains("opaqueB"))
        }
    }

    @Test fun invalidModelOverrideFailsBeforeMetadataOrModelRequest() = runBlocking {
        for (value in listOf("null", "123", "{}", "[]", "\"\"", "\"  \"")) Fixture(extra = Json.parseToJsonElement("""{"model":$value}""").jsonObject).use { f ->
            val error = runCatching { f.client.chatCompletionStream("A", user, emptyList(), true).toList() }.exceptionOrNull()
            assertEquals(ProviderError.InvalidInput.Reason.MODEL_OVERRIDE_INVALID, (error as ProviderError.InvalidInput).reason)
            assertTrue(f.requests.isEmpty())
            assertTrue(f.lookups.isEmpty())
        }
    }

    @Test fun noticesFromStartAndFallbackDeltaAreTypedDeduplicatedAndNotModelOutput() = runBlocking {
        val drop = """{"type":"thinking_dropped","path":"messages.1.content.0","reason":"prefix_binding_mismatch"}"""
        for (atStart in listOf(true, false)) {
            val sse = "event: message_start\ndata: {\"message\":{\"input_transformations\":${if (atStart) "[$drop]" else "[]"}}}\n\n" +
                "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"},\"input_transformations\":[$drop,$drop,{\"type\":\"future\",\"reason\":\"secret\"}]}\n\n" +
                "event: message_stop\ndata: {}\n\n"
            Fixture(caps = adaptive, sse = sse).use { f ->
                val chunks = f.client.chatCompletionStream("alias", user, emptyList(), true).toList()
                assertEquals(1, chunks.filterIsInstance<ChatChunk.ProviderNotice>().size)
                assertTrue(chunks.filterIsInstance<ChatChunk.Content>().isEmpty())
                assertTrue(chunks.filterIsInstance<ChatChunk.Reasoning>().isEmpty())
                assertEquals(1, f.posts.size)
                val transforms = f.events.filter { it.attributes["stage"] == "input_transformation" }
                assertEquals(1, transforms.size)
                assertEquals("prefix_binding_mismatch", transforms.single().attributes["reason"])
                assertNull(transforms.single().content)
                assertFalse(transforms.toString().contains("secret"))
            }
        }
    }

    @Test fun partialOutputAndProviderErrorsNeverNegotiateOrRepeatPost() = runBlocking {
        for ((status, sse) in listOf(400 to """{"error":{"type":"invalid_request_error","message":"adaptive thinking is not supported on this model"}}""",
            200 to "event: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"partial\"}}\n\nevent: error\ndata: {\"error\":{\"message\":\"failure\"}}\n\n")) {
            Fixture(caps = adaptive, postStatus = status, sse = sse).use { f ->
                val seen = mutableListOf<ChatChunk>()
                assertNotNull(runCatching { f.client.chatCompletionStream("alias", user, emptyList(), true).collect { seen += it } }.exceptionOrNull())
                assertEquals(1, f.posts.size)
                if (status == 200) assertTrue(seen.filterIsInstance<ChatChunk.Content>().isNotEmpty())
            }
        }
    }

    @Test fun nullOptionalTransformationsDoNotDiscardUsageOrStopReason() = runBlocking {
        val sse = "event: message_start\ndata: {\"message\":{\"input_transformations\":null,\"usage\":{\"input_tokens\":12}}}\n\n" +
            "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"},\"input_transformations\":null,\"usage\":{\"output_tokens\":7}}\n\n" +
            "event: message_stop\ndata: {}\n\n"
        Fixture(caps = adaptive, sse = sse).use { f ->
            val chunks = f.client.chatCompletionStream("alias", user, emptyList(), true).toList()
            // The client emits message_stop first, then the terminal Finish with final usage.
            val finish = chunks.filterIsInstance<ChatChunk.Finish>().last()
            assertEquals(12L, finish.usage!!.inputTokens)
            assertEquals(7L, finish.usage!!.outputTokens)
            assertEquals("end_turn", finish.reason)
            assertTrue(chunks.filterIsInstance<ChatChunk.ProviderNotice>().isEmpty())
        }
    }

    private data class Request(val method: String, val path: String, val body: JsonObject, val beta: String?)
    private class Fixture(native: Boolean = true, caps: AnthropicThinkingCapabilities? = null,
        extra: JsonObject? = null, metadata: String = """{"id":"alias"}""", getStatus: Int = 200,
        afterFirstGetStatus: Int? = null, capsById: (String) -> AnthropicThinkingCapabilities? = { caps },
        postStatus: Int = 200, sse: String = "event: message_start\ndata: {\"message\":{}}\n\nevent: message_stop\ndata: {}\n\n") : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val lookups = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent>()
        private val recorder = object : ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder {
            override fun record(event: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent, secrets: Collection<String>) { events += event }
            override fun health() = ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth(true, false, 0, 0)
        }
        val posts get() = requests.filter { it.method == "POST" }
        val base = if (native) "https://api.anthropic.com/v1" else "https://proxy.example/v1"
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            createContext("/") { exchange ->
                val raw = exchange.requestBody.use { it.readBytes().decodeToString() }
                requests += Request(exchange.requestMethod, exchange.requestURI.rawPath,
                    if (raw.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(raw).jsonObject,
                    exchange.requestHeaders.getFirst("anthropic-beta"))
                val get = exchange.requestMethod == "GET"
                val bytes = (if (get) metadata else sse).toByteArray()
                exchange.responseHeaders.add("Content-Type", if (get || postStatus != 200) "application/json" else "text/event-stream")
                val status = if (!get) postStatus else if (requests.count { it.method == "GET" } > 1) afterFirstGetStatus ?: getStatus else getStatus
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        private val http = HttpClient(OkHttp) {
            install(SSE)
            install(ContentNegotiation) { json(ru.outsideproarts.oassist.core.net.WireJson) }
            expectSuccess = true
        }.apply {
            plugin(HttpSend).intercept { request ->
                request.url.protocol = URLProtocol.HTTP
                request.url.host = "127.0.0.1"
                request.url.port = server.address.port
                execute(request)
            }
        }
        val client = AnthropicClient(EndpointConfig(recorder, ModelProtocol.ANTHROPIC_MESSAGES, base,
            ProviderAuth(apiKey = "test".toCharArray()), extraBody = extra), http) { lookups += it; ModelInfo(it, anthropicThinking = capsById(it)) }
        override fun close() { http.close(); server.stop(0) }
    }
}
