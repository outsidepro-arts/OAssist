package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

class McpProtocolWireTest {
    private fun HttpRequestData.message() = McpJson.parseToJsonElement((body as TextContent).text).jsonObject
    private fun HttpRequestData.method() = message()["method"]!!.jsonPrimitive.content
    private fun MockRequestHandleScope.rpc(request: HttpRequestData, result: String, session: String? = null) =
        respond("""{"jsonrpc":"2.0","id":${request.message()["id"]},"result":$result}""", HttpStatusCode.OK,
            io.ktor.http.Headers.build {
                append("Content-Type", "application/json")
                session?.let { append(McpClient.SESSION_HEADER, it) }
            })
    private fun MockRequestHandleScope.initialize(request: HttpRequestData, session: String? = null) =
        rpc(request, """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"strict","version":"1"}}""", session)

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun handshakeIsNotificationAndVersionAppliesToEverySubsequentRequest() = runBlocking {
        for (session in listOf(null, "session-1")) {
            val methods = mutableListOf<String>()
            val engine = MockEngine { request ->
                val message = request.message()
                val method = request.method()
                methods += method
                assertEquals("2.0", message["jsonrpc"]!!.jsonPrimitive.content)
                assertEquals("Bearer secret", request.headers["Authorization"])
                assertTrue(request.headers["Accept"]!!.contains("application/json"))
                assertTrue(request.headers["Accept"]!!.contains("text/event-stream"))
                if (method == "initialize") {
                    assertNull(request.headers[McpClient.SESSION_HEADER])
                    assertNull(request.headers[McpClient.PROTOCOL_HEADER])
                    assertEquals(McpClient.PROTOCOL_VERSION, message["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
                    initialize(request, session)
                } else {
                    assertEquals(session, request.headers[McpClient.SESSION_HEADER])
                    assertEquals(listOf(McpClient.PROTOCOL_VERSION), request.headers.getAll(McpClient.PROTOCOL_HEADER))
                    when (method) {
                        "notifications/initialized" -> {
                            assertFalse(message.containsKey("id"))
                            respond("", HttpStatusCode.Accepted)
                        }
                        "tools/list" -> rpc(request, """{"tools":[]}""", "must-not-replace-session")
                        else -> rpc(request, """{"content":[]}""")
                    }
                }
            }
            HttpClient(engine).use { http ->
                val client = McpClient(Diagnostics, http, "https://example.test/mcp", mapOf(
                    "Authorization" to "Bearer secret", McpClient.PROTOCOL_HEADER to "wrong", McpClient.SESSION_HEADER to "wrong"))
                client.listTools()
                client.callTool("read", JsonObject(emptyMap()))
                client.listTools()
            }
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "tools/list"), methods)
        }
    }

    @Test fun rejectedInitializeAndUnsupportedVersionNeverPublishSession() = runBlocking {
        for (result in listOf(
            """"error":{"code":-32603,"message":"initialize denied"}""",
            """"result":{"protocolVersion":"2025-03-26"}""",
            """"result":{"protocolVersion":"2099-01-01"}""",
            """"result":{}""",
        )) {
            val methods = mutableListOf<String>()
            val engine = MockEngine { request ->
                methods += request.method()
                assertNull(request.headers[McpClient.SESSION_HEADER])
                respond("""{"jsonrpc":"2.0","id":${request.message()["id"]},$result}""", HttpStatusCode.OK,
                    headersOf("Content-Type" to listOf("application/json"), McpClient.SESSION_HEADER to listOf("uncommitted")))
            }
            HttpClient(engine).use { http ->
                val client = McpClient(Diagnostics, http, "https://example.test")
                repeat(2) { assertTrue(failure { client.listTools() } is McpError) }
            }
            assertEquals(listOf("initialize", "initialize"), methods)
        }
    }

    @Test fun initializedFailureRestartsWholeHandshakeWithoutRecursion() = runBlocking {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.OK)) {
            val methods = mutableListOf<String>()
            var notifications = 0
            val engine = MockEngine { request ->
                methods += request.method()
                when (request.method()) {
                    "initialize" -> initialize(request, "session-${methods.size}")
                    "notifications/initialized" -> {
                        notifications++
                        if (notifications == 1) respond("", status) else respond("", HttpStatusCode.Accepted)
                    }
                    else -> rpc(request, """{"tools":[]}""")
                }
            }
            HttpClient(engine).use { http ->
                val client = McpClient(Diagnostics, http, "https://example.test")
                failure { client.listTools() }
                assertTrue(client.listTools().isEmpty())
            }
            assertEquals(listOf("initialize", "notifications/initialized", "initialize", "notifications/initialized", "tools/list"), methods)
        }
    }

    @Test fun sseMultilineSkipsProgressForeignIdsAndServerRequestsAndReturnsBeforeEof() = runBlocking {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val channel = ByteChannel(autoFlush = true)
            val engine = MockEngine { request ->
                when (request.method()) {
                    "initialize" -> initialize(request)
                    "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                    else -> {
                        val id = request.message()["id"]
                        channel.writeStringUtf8(listOf(
                            "\uFEFF: heartbeat", "",
                            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}", "",
                            "data: {\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{}}", "",
                            "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"ping\"}", "",
                            "event: message", "id: sse-id-is-not-rpc-id", "data: {", "data: \"jsonrpc\":\"2.0\",\"id\":$id,",
                            "data: \"result\":{\"tools\":[{\"name\":\"matched\"}]}}", "", "",
                            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}", "", "",
                        ).joinToString(newline))
                        respond(channel, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream; charset=utf-8"))
                    }
                }
            }
            try {
                HttpClient(engine).use { http ->
                    val client = McpClient(Diagnostics, http, "https://example.test")
                    assertEquals("matched", withTimeout(5_000) { client.listTools() }.single().name)
                }
            } finally { channel.cancel(null) }
        }
    }

    @Test fun mismatchedMissingMalformedAndIncompleteResponsesFailAndRpcErrorIsPreserved() = runBlocking {
        val responses = listOf(
            "application/json" to { id: String -> """{"jsonrpc":"2.0","id":"$id","result":{"tools":[]}}""" },
            "application/json" to { _: String -> """{"jsonrpc":"2.0","id":-1,"result":{}}""" },
            "application/json" to { _: String -> """{"jsonrpc":"2.0","method":"notifications/progress"}""" },
            "application/json" to { id: String -> """{"jsonrpc":"2.0","id":$id,"result":{},"error":{"code":-1,"message":"bad"}}""" },
            "text/event-stream" to { id: String -> "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{}}\n" },
            "text/event-stream" to { _: String -> ":heartbeat\n\ndata: not-json\n\n" },
            "text/event-stream" to { id: String -> "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":-1,\"message\":\"denied-marker\"}}\n\n" },
        )
        for ((type, body) in responses) {
            val engine = MockEngine { request ->
                when (request.method()) {
                    "initialize" -> initialize(request)
                    "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                    else -> respond(body(request.message()["id"].toString()), HttpStatusCode.OK, headersOf("Content-Type", type))
                }
            }
            HttpClient(engine).use { http ->
                val error = failure { McpClient(Diagnostics, http, "https://example.test").listTools() }
                assertTrue(error.toString(), error is McpError)
                if (body("1").contains("denied-marker")) assertTrue(error.message!!.contains("denied-marker"))
            }
        }
    }

    @Test fun cancellationDuringInitializedAllowsFreshHandshakeAndConcurrentCallsShareIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var initializes = 0
        var notifications = 0
        var calls = 0
        val engine = MockEngine { request ->
            when (request.method()) {
                "initialize" -> { initializes++; initialize(request, "session-$initializes") }
                "notifications/initialized" -> {
                    notifications++
                    if (notifications == 1) { entered.complete(Unit); release.await() }
                    respond("", HttpStatusCode.Accepted)
                }
                else -> { calls++; rpc(request, """{"tools":[]}""") }
            }
        }
        HttpClient(engine).use { http ->
            val client = McpClient(Diagnostics, http, "https://example.test")
            val cancelled = launch { client.listTools() }
            entered.await()
            assertEquals(0, calls)
            cancelled.cancelAndJoin()
            release.complete(Unit)
            val first = async { client.listTools() }
            val second = async { client.listTools() }
            first.await(); second.await()
            assertEquals(2, initializes)
            assertEquals(2, notifications)
            assertEquals(2, calls)
        }
    }

    @Test fun sessionRecoveryIsBoundedAndStateless404DoesNotRetry() = runBlocking {
        for (session in listOf(null, "expired")) {
            var initializes = 0
            var calls = 0
            val engine = MockEngine { request ->
                when (request.method()) {
                    "initialize" -> { initializes++; initialize(request, session) }
                    "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                    else -> { calls++; respond("gone", HttpStatusCode.NotFound) }
                }
            }
            HttpClient(engine).use { http ->
                failure { McpClient(Diagnostics, http, "https://example.test").listTools() }
            }
            assertEquals(if (session == null) 1 else 2, initializes)
            assertEquals(if (session == null) 1 else 2, calls)
        }
    }

    @Test fun late404DoesNotInvalidateReplacementSession() = runBlocking {
        val oldCallEntered = CompletableDeferred<Unit>()
        val releaseOldCall = CompletableDeferred<Unit>()
        var initializes = 0
        var oldCalls = 0
        val engine = MockEngine { request ->
            when (request.method()) {
                "initialize" -> { initializes++; initialize(request, "session-$initializes") }
                "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                else -> {
                    if (request.headers[McpClient.SESSION_HEADER] == "session-1") {
                        oldCalls++
                        if (oldCalls == 1) { oldCallEntered.complete(Unit); releaseOldCall.await() }
                        respond("gone", HttpStatusCode.NotFound)
                    } else {
                        assertEquals("session-2", request.headers[McpClient.SESSION_HEADER])
                        rpc(request, """{"tools":[]}""")
                    }
                }
            }
        }
        HttpClient(engine).use { http ->
            val client = McpClient(Diagnostics, http, "https://example.test")
            val old = async { client.listTools() }
            oldCallEntered.await()
            client.listTools()
            assertEquals(2, initializes)
            releaseOldCall.complete(Unit)
            old.await()
            client.listTools()
            assertEquals(2, initializes)
        }
    }
}
