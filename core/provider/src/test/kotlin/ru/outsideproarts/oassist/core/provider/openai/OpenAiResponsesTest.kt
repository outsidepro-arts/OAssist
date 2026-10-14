package ru.outsideproarts.oassist.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.ToolCallRef

class OpenAiResponsesTest {

    private val fakeSecrets = object : ru.outsideproarts.oassist.core.data.secret.SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = "resp-key".toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun mockClient(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient {
        val engine = MockEngine { request -> handler(request) }
        return HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(ProviderJson) }
        }
    }

    @Test
    fun mapResponsesInput_systemToInstructions_andRolesToItems() {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "Ты ассистент"),
            ChatMessage(ChatRole.USER, "Привет"),
            ChatMessage(ChatRole.ASSISTANT, "Здравствуй"),
            ChatMessage(
                ChatRole.ASSISTANT,
                "",
                toolCalls = listOf(ToolCallRef(id = "call_1", name = "device_state", argumentsJson = "{}")),
            ),
            ChatMessage(ChatRole.TOOL, "screen=on", toolCallId = "call_1"),
        )

        val (instructions, items) = OpenAiResponsesClient.mapResponsesInput(messages)

        assertEquals("Ты ассистент", instructions)
        assertEquals(4, items.size)

        val user = items[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)

        val assistant = items[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("Здравствуй", assistant["content"]!!.jsonPrimitive.content)

        val functionCall = items[2].jsonObject
        assertEquals("function_call", functionCall["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", functionCall["call_id"]!!.jsonPrimitive.content)
        assertEquals("device_state", functionCall["name"]!!.jsonPrimitive.content)

        val output = items[3].jsonObject
        assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
        assertEquals("screen=on", output["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapResponsesInput_toolImages_goAsSeparateUserItem() {
        val messages = listOf(
            ChatMessage(
                ChatRole.TOOL,
                "снимок",
                toolCallId = "call_1",
                images = listOf(ru.outsideproarts.oassist.core.provider.ImagePart(imageBase64 = "AAAA")),
            ),
        )

        val (_, items) = OpenAiResponsesClient.mapResponsesInput(messages)

        assertEquals(2, items.size)
        val user = items[1].jsonObject
        val content = user["content"]!!.jsonArray
        assertEquals("input_image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapResponsesInput_noSystem_noInstructions() {
        val (instructions, items) = OpenAiResponsesClient.mapResponsesInput(
            listOf(ChatMessage(ChatRole.USER, "Привет")),
        )

        assertNull(instructions)
        assertEquals(1, items.size)
    }

    @Test
    fun toResponsesTool_isFlatSchema() {
        val tool = ToolSchemaDto(
            function = ToolFunctionSchemaDto(
                name = "alarm_set",
                description = "Установка будильника",
                parameters = kotlinx.serialization.json.buildJsonObject { put("type", "object") },
            ),
        ).toResponsesTool()

        assertEquals("function", tool.type)
        assertEquals("alarm_set", tool.name)
        assertEquals("Установка будильника", tool.description)
    }

    @Test
    fun usageDto_mapsToProviderUsage() {
        val dto = ProviderJson.decodeFromString(
            ResponsesUsageDto.serializer(),
            """{"input_tokens":100,"output_tokens":50,"total_tokens":150,"input_tokens_details":{"cached_tokens":30}}""",
        )

        val usage = dto.toProviderUsage()!!

        assertEquals(100L, usage.inputTokens)
        assertEquals(50L, usage.outputTokens)
        assertEquals(150L, usage.totalTokens)
        assertEquals(30L, usage.cachedTokens)
    }

    @Test
    fun streamEvent_parsesDeltaAndCompletion() {
        val delta = ProviderJson.decodeFromString(
            ResponsesStreamEventDto.serializer(),
            """{"type":"response.output_text.delta","item_id":"msg_1","delta":"Привет"}""",
        )
        assertEquals("response.output_text.delta", delta.type)
        assertEquals("Привет", delta.delta)

        val completed = ProviderJson.decodeFromString(
            ResponsesStreamEventDto.serializer(),
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}""",
        )
        assertEquals("completed", completed.response?.status)
        assertEquals(2L, completed.response?.usage?.outputTokens)
    }

    @Test
    fun probe_returnsTools_on200_andEmpty_on400() = runTest {
        var bodies = ""
        val ok = mockClient { request ->
            bodies = (request.body as TextContent).text
            respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val client = ModelClient.create(
            ru.outsideproarts.oassist.core.model.ProviderProfile(
                id = "p1",
                name = "OpenAI",
                kind = ru.outsideproarts.oassist.core.model.ProviderKind.OPENAI_RESPONSES,
                baseUrl = "https://api.openai.com/v1",
                apiKeyRef = "provider.p1.apiKey",
            ),
            fakeSecrets,
            ok,
        )

        val caps = client.probeCapabilities("gpt-5.5")

        assertEquals(setOf(ru.outsideproarts.oassist.core.model.ModelCapability.TOOLS), caps)
        assertTrue(bodies.contains("\"name\":\"probe\""))
        assertFalse(bodies.contains("\"function\":"))

        val bad = mockClient {
            respond("{}", status = HttpStatusCode.BadRequest, headers = headersOf("Content-Type", "application/json"))
        }
        val badClient = ModelClient.create(
            ru.outsideproarts.oassist.core.model.ProviderProfile(
                id = "p2",
                name = "OpenAI",
                kind = ru.outsideproarts.oassist.core.model.ProviderKind.OPENAI_RESPONSES,
                baseUrl = "https://api.openai.com/v1",
            ),
            fakeSecrets,
            bad,
        )

        assertEquals(emptySet<ru.outsideproarts.oassist.core.model.ModelCapability>(), badClient.probeCapabilities("gpt-5.5"))
    }

    @Test
    fun fetchModels_usesOpenAiStyleList() = runTest {
        var path = ""
        var auth = ""
        val client = ModelClient.create(
            ru.outsideproarts.oassist.core.model.ProviderProfile(
                id = "p1",
                name = "OpenAI",
                kind = ru.outsideproarts.oassist.core.model.ProviderKind.OPENAI_RESPONSES,
                baseUrl = "https://api.openai.com/v1",
                apiKeyRef = "provider.p1.apiKey",
            ),
            fakeSecrets,
            mockClient { request ->
                path = request.url.encodedPath
                auth = request.headers["Authorization"].orEmpty()
                respond("""{"data":[{"id":"gpt-5.5"}]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
            },
        )

        val models = client.fetchModels()

        assertEquals("/v1/models", path)
        assertEquals("Bearer resp-key", auth)
        assertEquals(listOf("gpt-5.5"), models.map { it.id })
    }
}
