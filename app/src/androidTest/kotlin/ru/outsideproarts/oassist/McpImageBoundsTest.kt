package ru.outsideproarts.oassist

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.mcp.McpClient
import ru.outsideproarts.oassist.core.agent.mcp.McpTool
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class McpImageBoundsTest {
    private fun largePng(): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { output ->
            val row = ByteArray(10_000 * 4 + 1)
            repeat(10_000) { output.write(row) }
        }
        val png = ByteArrayOutputStream()
        val output = DataOutputStream(png)
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(name); update(bytes) }
            output.writeInt(bytes.size); output.write(name); output.write(bytes); output.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(10_000); it.writeInt(10_000); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
        return png.toByteArray()
    }

    @Test fun actualBitmapFactoryChecksDimensionsBeforeToolResult() = runBlocking {
        val png = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
        bitmap.recycle()
        val small = Base64.getEncoder().encodeToString(png.toByteArray())
        val largeBytes = largePng()
        assertTrue(largeBytes.size < 4 * 1024 * 1024)
        val large = Base64.getEncoder().encodeToString(largeBytes)
        var payload = small
        val http = HttpClient(MockEngine { request ->
            val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val result = when (json["method"]!!.jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-06-18"}"""
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                else -> """{"content":[{"type":"image","data":"$payload","mimeType":"image/png"}]}"""
            }
            respond("""{"jsonrpc":"2.0","id":${json["id"]},"result":$result}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val tool = McpTool("s", "S", "read", "read", null, JsonObject(emptyMap()), McpClient(Diagnostics, http, "https://server.test"))
            val valid = tool.execute(JsonObject(emptyMap()))
            assertTrue(valid.success)
            assertEquals(small, valid.images.single().imageBase64)
            payload = large
            val oversized = tool.execute(JsonObject(emptyMap()))
            assertTrue(oversized.success)
            assertTrue(oversized.images.isEmpty())
            assertTrue(oversized.output.contains("полностью недоступно"))
            assertTrue(oversized.output.contains("не повторяйте"))
        } finally { http.close() }
    }
}
