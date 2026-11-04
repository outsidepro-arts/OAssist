package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.ApprovalStates
import ru.outsideproarts.oassist.core.agent.ToolRegistry
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

class McpCatalogTest {
    private val empty = JsonObject(emptyMap())

    @Test fun registryDispatchesFullIdentitiesRegardlessOfOrderAndKeepsRemoteNames() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val identities = listOf(
            "1730712345000" to "read", "1730712345999" to "read",
            "server" to "a.b", "server" to "a_b",
            "server" to "a".repeat(100), "server" to "a".repeat(99) + "b",
            "s.rver" to "read", "srver" to "read",
        )
        HttpClient(MockEngine { request ->
            val message = McpJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            val result = when (message["method"]!!.jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-06-18"}"""
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                else -> {
                    calls += request.url.host to message["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    """{"content":[{"type":"text","text":"ok"}]}"""
                }
            }
            respond("""{"jsonrpc":"2.0","id":${message["id"]},"result":$result}""",
                headers = headersOf("Content-Type", "application/json"))
        }).use { http ->
            val tools = identities.mapIndexed { i, (server, remote) ->
                McpTool(server, "Server $i", remote, McpTool.displayName(server, remote), null, empty,
                    McpClient(Diagnostics, http, "https://server$i.test"))
            }
            for (ordered in listOf(tools, tools.reversed())) {
                val registry = ToolRegistry(ordered, diagnostics = Diagnostics)
                assertEquals(tools.size, registry.schemas().size)
                tools.forEachIndexed { i, tool ->
                    assertTrue(tool.schema.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
                    assertTrue(registry.execute(tool.schema.name, empty).success)
                    assertEquals("server$i.test" to identities[i].second, calls.last())
                }
                val allowed = mapOf(ToolGroups.mcp(identities[0].first) to ApprovalState.ALLOW)
                assertEquals(ApprovalState.ALLOW, ApprovalStates.stateOf(allowed, registry.groupKey(tools[0].schema.name)))
                assertEquals(ApprovalState.ASK, ApprovalStates.stateOf(allowed, registry.groupKey(tools[1].schema.name)))
                assertFalse(registry.execute(McpTool.legacyDisplayName(identities[0].first, "read"), empty).success)
            }
            val disabled = ToolRegistry(tools, setOf(McpTool.legacyDisplayName(identities[0].first, "read")), Diagnostics)
            assertFalse(disabled.isEnabled(tools[0].schema.name))
            assertFalse(disabled.isEnabled(tools[1].schema.name))
            assertFalse(disabled.execute(tools[1].schema.name, empty).success)
            assertEquals(tools.size - 2, disabled.schemas().size)
            assertTrue(disabled.isEnabled(tools[2].schema.name))
            try {
                ToolRegistry(listOf(tools[0], tools[0]), diagnostics = Diagnostics)
                fail("Duplicate catalog must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
