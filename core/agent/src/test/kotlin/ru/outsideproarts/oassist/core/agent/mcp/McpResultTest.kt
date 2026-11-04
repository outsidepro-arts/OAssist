package ru.outsideproarts.oassist.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.*
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.awt.image.BufferedImage
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

class McpResultTest {
    private val smallPng = ByteArrayOutputStream().also {
        assertTrue(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", it))
    }.toByteArray().let { Base64.getEncoder().encodeToString(it) }

    private fun imageBounds(bytes: ByteArray): Triple<Int, Int, String?>? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            return try {
                reader.input = input
                Triple(reader.getWidth(0), reader.getHeight(0), "image/${reader.formatName.lowercase()}")
            } finally { reader.dispose() }
        }
    }

    // Stream uncompressed scanlines into DEFLATE: never allocate the decoded bitmap.
    private fun largePng(): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { out ->
            val row = ByteArray(10_000 * 4 + 1)
            repeat(10_000) { out.write(row) }
        }
        val png = ByteArrayOutputStream()
        val out = DataOutputStream(png)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(name); update(data) }
            out.writeInt(data.size); out.write(name); out.write(data); out.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(10_000); it.writeInt(10_000); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
        return png.toByteArray()
    }

    private suspend fun exercise(result: String, vision: Boolean = true): Pair<ChatChunk.ToolResult, ChatMessage> {
        val http = HttpClient(MockEngine { request ->
            val requestJson = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val response = when (requestJson["method"]!!.jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-06-18"}"""
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                else -> result
            }
            respond("""{"jsonrpc":"2.0","id":${requestJson["id"]},"result":$response}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val tool = McpTool("s", "Server", "read", "mcp_read", null, JsonObject(emptyMap()), McpClient(Diagnostics, http, "https://server.test"), imageBounds = ::imageBounds)
            val requests = mutableListOf<List<ChatMessage>>()
            val model = object : ModelClient {
                override suspend fun fetchModels(): List<ModelInfo> = emptyList()
                override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean): Flow<ChatChunk> {
                    requests += messages.toList()
                    return if (requests.size == 1) flowOf(ChatChunk.ToolCall("c", "mcp_read", "{}"), ChatChunk.Finish("tool_calls")) else flowOf(ChatChunk.Finish("stop"))
                }
            }
            val policy = InMemoryApprovalPolicy().apply { setRemembered(ToolGroups.mcp("s"), ApprovalState.ALLOW) }
            val chunks = AgentLoop(model, ToolRegistry(listOf(tool)), approvalPolicy = policy, visionEnabled = vision).stream("m", null, emptyList(), "q").toList()
            assertEquals(2, requests.size)
            return chunks.filterIsInstance<ChatChunk.ToolResult>().single() to requests.last().last { it.role == ChatRole.TOOL }
        } finally { http.close() }
    }

    @Test fun mixedResultReachesModelAndDisclosesOmissions() = runBlocking {
        val (chunk, message) = exercise("""{"content":[{"type":"text","text":"hello"},{"type":"resource","resource":{"uri":"file:///a","text":"contents"}},{"type":"resource_link","uri":"file:///b","name":"b"},{"type":"audio","data":"AAAA","mimeType":"audio/wav"},{"type":"image","data":"$smallPng","mimeType":"image/png"}],"structuredContent":{"answer":42}}""")
        assertFalse(chunk.isError)
        val data = Json.parseToJsonElement(message.content).jsonObject["data"]!!.jsonPrimitive.content
        for (part in listOf("hello", "file:///a", "contents", "file:///b", "structuredContent", "42", "audio")) assertTrue(data, data.contains(part))
        assertTrue(message.content.contains("частично недоступно"))
        assertEquals("$smallPng", message.images.single().imageBase64)
        assertEquals(chunk.output, data)
    }

    @Test fun imageAndVisionDisabled() = runBlocking {
        val wire = """{"content":[{"type":"image","data":"$smallPng","mimeType":"image/png"}]}"""
        val (chunk, message) = exercise(wire, false)
        assertFalse(chunk.isError)
        assertEquals(1, chunk.images.size)
        assertTrue(message.images.isEmpty())
        assertTrue(message.content.contains(IMAGE_DISABLED_NOTE.trim()))
    }

    @Test fun errorUnsupportedEmptyAndMalformedAreNotSilentSuccess() = runBlocking {
        for (wire in listOf(
            """{"content":[{"type":"audio","data":"AA==","mimeType":"audio/wav"}]}""",
            """{"content":[{"type":"resource","resource":{"uri":"file:///a","blob":"AAAA"}}]}""",
            """{"content":[{"type":"future_type"}]}"""
        )) {
            val (chunk, message) = exercise(wire)
            assertFalse(wire, chunk.isError)
            val envelope = Json.parseToJsonElement(message.content).jsonObject
            assertEquals("success", envelope["status"]!!.jsonPrimitive.content)
            assertTrue(message.content.contains("полностью недоступно"))
            assertTrue(message.content.contains("не повторяйте"))
        }
        for (wire in listOf(
            """{"content":[{"type":"image","data":"!","mimeType":"image/png"}]}""",
            """{"content":[{"type":"text"}]}""", """{}""",
            """{"content":[{"type":"text","text":"denied"}],"isError":true}""",
            """{"content":"invalid"}"""
        )) {
            val (chunk, message) = exercise(wire)
            assertTrue(wire, chunk.isError)
            assertTrue(wire, message.content.isNotBlank())
        }
    }

    @Test fun limitsPreserveNoticeAndStructuredOnly() = runBlocking {
        val (empty, _) = exercise("""{"content":[]}""")
        assertFalse(empty.isError)
        assertEquals("(пусто)", empty.output)
        val (structured, msg) = exercise("""{"content":[],"structuredContent":{"ok":true}}""")
        assertFalse(structured.isError)
        assertTrue(Json.parseToJsonElement(msg.content).jsonObject["data"]!!.jsonPrimitive.content.contains("\"ok\":true"))
        val text = "x".repeat(9000)
        val (chunk, message) = exercise("""{"content":[{"type":"text","text":"$text"},{"type":"audio","data":"x","mimeType":"audio/wav"}]}""")
        assertFalse(chunk.isError)
        assertTrue(message.content.contains("обрезано"))
        assertTrue(message.content.contains("audio"))
        assertTrue(message.content.length < 8300)
    }

    @Test fun imageCountAndSizeAreBoundedAndReported() = runBlocking {
        val image = """{"type":"image","data":"$smallPng","mimeType":"image/png"}"""
        val oversized = """{"type":"image","data":"${"A".repeat(4 * 1024 * 1024 + 4)}","mimeType":"image/png"}"""
        val (chunk, message) = exercise("""{"content":[${List(5) { image }.joinToString(",")},$oversized]}""")
        assertFalse(chunk.isError)
        assertEquals(4, chunk.images.size)
        assertTrue(message.content.contains("image"))
        assertTrue(message.content.contains("частично недоступно"))
    }

    @Test fun validHighlyCompressedLargeImageIsNotDecodedOrDelivered() = runBlocking {
        val bytes = largePng()
        assertTrue(bytes.size < 4 * 1024 * 1024)
        assertEquals(Triple(10_000, 10_000, "image/png"), imageBounds(bytes))
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            try {
                reader.input = input
                val params = reader.defaultReadParam.apply { sourceRegion = Rectangle(0, 0, 1, 1) }
                assertEquals(1, reader.read(0, params).width)
            } finally { reader.dispose() }
        }
        assertNotNull(ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(smallPng))))
        val large = Base64.getEncoder().encodeToString(bytes)
        val (chunk, message) = exercise("""{"content":[{"type":"image","data":"$large","mimeType":"image/png"}]}""", false)
        assertFalse(chunk.isError)
        assertTrue(chunk.images.isEmpty())
        assertTrue(message.images.isEmpty())
        assertTrue(message.content.contains("полностью недоступно"))
        assertTrue(message.content.contains("не повторяйте"))
    }

    @Test fun mcpResultReachesActualOpenAiRequest() = runBlocking {
        val (_, message) = exercise("""{"content":[{"type":"image","data":"$smallPng","mimeType":"image/png"},{"type":"audio","data":"AA==","mimeType":"audio/wav"}],"structuredContent":{"value":7}}""")
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            body = exchange.requestBody.bufferedReader().readText()
            val response = "data: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val http = HttpClient(OkHttp) { install(SSE); install(ContentNegotiation) { json() } }
        val secrets = object : SecretStore {
            override suspend fun put(alias: String, plaintext: CharArray) = Unit
            override suspend fun get(alias: String): CharArray? = null
            override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) = Unit
            override suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?> = null to null
            override suspend fun contains(alias: String) = false
            override suspend fun delete(alias: String) = Unit
            override suspend fun clearAll() = Unit
            override suspend fun aliases(): List<String> = emptyList()
        }
        try {
            val profile = ProviderProfile("p", "test", ProviderPreset.CUSTOM, "http://127.0.0.1:${server.address.port}")
            val provider = OpenAiCompatibleClient.fromProfile(Diagnostics, profile, secrets, http)
            provider.chatCompletionStream("m", listOf(message), emptyList()).toList()
            val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            val toolContent = messages[0].jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(toolContent.contains("structuredContent"))
            assertTrue(toolContent.contains("audio"))
            assertTrue(messages[1].toString().contains("data:image/png;base64,$smallPng"))
        } finally { http.close(); server.stop(0) }
    }
}
