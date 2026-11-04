package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

class McpPaginationWireTest {
    private data class Reply(
        val result: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val rpcError: Boolean = false,
    )
    private class Fixture(val page: (Int, String?) -> Reply) : AutoCloseable {
        val requests = mutableListOf<Pair<Int, String?>>()
        var handshakes = 0
        val http = HttpClient(MockEngine { request ->
            val message = McpJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = message["method"]!!.jsonPrimitive.content
            val reply = when (method) {
                "initialize" -> {
                    handshakes++
                    Reply("""{"protocolVersion":"2025-06-18"}""")
                }
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                "tools/list" -> {
                    assertEquals("session-$handshakes", request.headers[McpClient.SESSION_HEADER])
                    assertEquals(McpClient.PROTOCOL_VERSION, request.headers[McpClient.PROTOCOL_HEADER])
                    val params = message["params"]!!.jsonObject
                    assertTrue(params.keys.all { it == "cursor" })
                    val cursor = params["cursor"]?.jsonPrimitive?.content
                    requests += handshakes to cursor
                    page(handshakes, cursor)
                }
                else -> error("Unexpected method $method")
            }
            val payloadKey = if (reply.rpcError) "error" else "result"
            respond("""{"jsonrpc":"2.0","id":${message["id"]},"$payloadKey":${reply.result}}""",
                reply.status, headersOf(
                    "Content-Type" to listOf("application/json"),
                    McpClient.SESSION_HEADER to listOf("session-$handshakes")))
        })
        val client = McpClient(Diagnostics, http, "https://catalog.test")
        override fun close() = http.close()
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected catalog failure")
    }

    @Test fun followsOpaqueCursorsIncludingEmptyPageAndRestartsEachListing() = runBlocking {
        Fixture { _, cursor ->
            Reply(when (cursor) {
                null -> """{"tools":[{"name":"one"}],"nextCursor":"opaque /+="}"""
                "opaque /+=" -> """{"tools":[],"nextCursor":""}"""
                "" -> """{"tools":[{"name":"two"}]}"""
                else -> error("Unexpected cursor")
            })
        }.use { f ->
            repeat(2) { assertEquals(listOf("one", "two"), f.client.listTools().map { it.name }) }
            assertEquals(listOf(null, "opaque /+=", "", null, "opaque /+=", ""), f.requests.map { it.second })
            assertEquals(1, f.handshakes)
        }
    }

    @Test fun cyclesDuplicatesMalformedAndLateErrorsNeverReturnPartialCatalog() = runBlocking {
        val badPages = listOf(
            Reply("""{"tools":[],"nextCursor":"A"}"""),
            Reply("""{"tools":[{"name":"one"}]}"""),
            Reply("""{"nextCursor":"B"}"""),
            Reply("""{"tools":[]}""", HttpStatusCode.InternalServerError),
            Reply("""{"code":-32603,"message":"later page failed"}""", rpcError = true),
        )
        for (bad in badPages) {
            Fixture { _, cursor ->
                if (cursor == null) Reply("""{"tools":[{"name":"one"}],"nextCursor":"A"}""") else bad
            }.use { f ->
                failure { f.client.listTools() }
                assertEquals(2, f.requests.size)
            }
        }
        Fixture { _, cursor ->
            Reply(when (cursor) {
                null -> """{"tools":[],"nextCursor":"A"}"""
                "A" -> """{"tools":[],"nextCursor":"B"}"""
                else -> """{"tools":[],"nextCursor":"A"}"""
            })
        }.use { f ->
            assertTrue(failure { f.client.listTools() } is McpError)
            assertEquals(3, f.requests.size)
        }
        Fixture { _, _ -> Reply("""{"tools":[{"name":"one"},{"name":"one"}]}""") }.use { f ->
            assertTrue(failure { f.client.listTools() } is McpError)
        }
    }

    @Test fun failureDoesNotCachePartialCatalogOrPoisonNextListing() = runBlocking {
        var failLaterPage = true
        Fixture { _, cursor ->
            when {
                cursor == null -> Reply("""{"tools":[{"name":"one"}],"nextCursor":"next"}""")
                failLaterPage -> Reply("{}", HttpStatusCode.InternalServerError)
                else -> Reply("""{"tools":[{"name":"two"}]}""")
            }
        }.use { f ->
            failure { f.client.listTools() }
            failLaterPage = false
            assertEquals(listOf("one", "two"), f.client.listTools().map { it.name })
            assertEquals(listOf(null, "next", null, "next"), f.requests.map { it.second })
        }
    }

    @Test fun midCatalog404DiscardsOldPagesAndCursorThenRestartsOnlyOnce() = runBlocking {
        for (expireAgain in listOf(false, true)) {
            Fixture { session, cursor ->
                when {
                    session == 1 && cursor == null -> Reply("""{"tools":[{"name":"stale"}],"nextCursor":"old"}""")
                    session == 1 || expireAgain && cursor != null -> Reply("{}", HttpStatusCode.NotFound)
                    cursor == null -> Reply("""{"tools":[{"name":"fresh"}],"nextCursor":"new"}""")
                    else -> Reply("""{"tools":[{"name":"last"}]}""")
                }
            }.use { f ->
                if (expireAgain) failure { f.client.listTools() }
                else assertEquals(listOf("fresh", "last"), f.client.listTools().map { it.name })
                assertEquals(listOf(1 to null, 1 to "old", 2 to null, 2 to "new"), f.requests)
                assertEquals(2, f.handshakes)
            }
        }
    }

    @Test fun pageToolAndAggregateSizeBudgetsFailRatherThanTruncate() = runBlocking {
        var pageNumber = 0
        Fixture { _, _ -> Reply("""{"tools":[],"nextCursor":"${++pageNumber}"}""") }.use { f ->
            assertTrue(failure { f.client.listTools() } is McpError)
            assertEquals(100, f.requests.size)
        }
        val tooMany = (0..10_000).joinToString(",") { """{"name":"t$it"}""" }
        Fixture { _, _ -> Reply("""{"tools":[$tooMany]}""") }.use { f ->
            assertTrue(failure { f.client.listTools() } is McpError)
        }
        var page = 0
        val description = "x".repeat(100_000)
        Fixture { _, _ ->
            page++
            Reply("""{"tools":[{"name":"t$page","description":"$description"}],"nextCursor":"$page"}""")
        }.use { f ->
            assertTrue(failure { f.client.listTools() } is McpError)
            assertTrue(f.requests.size < 100)
        }
    }

    @Test fun lateCancellationIsNotConvertedToPartialSuccess() = runBlocking {
        Fixture { _, cursor ->
            if (cursor != null) throw CancellationException("cancel")
            Reply("""{"tools":[{"name":"one"}],"nextCursor":"next"}""")
        }.use { f -> assertTrue(failure { f.client.listTools() } is CancellationException) }
    }
}
