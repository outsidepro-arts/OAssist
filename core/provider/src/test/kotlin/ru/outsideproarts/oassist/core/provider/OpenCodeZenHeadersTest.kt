package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient
import ru.outsideproarts.oassist.core.provider.opencode.OpenCodeZen

class OpenCodeZenHeadersTest {

    private val emptySecrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = null
        override suspend fun contains(alias: String): Boolean = false
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    /** Секреты с API-ключом для alias первого попавшегося профиля. */
    private fun secretsWithKey(key: String) = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = key.toCharArray()
        override suspend fun contains(alias: String): Boolean = true
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun mockClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(io.ktor.client.plugins.sse.SSE)
        install(ContentNegotiation) { json(ProviderJson) }
    }

    private val zenProfile = ProviderProfile(
        id = "p1",
        name = "Zen",
        preset = ProviderPreset.OPENCODE,
        baseUrl = "https://opencode.ai/zen/go/v1",
        apiKeyRef = "provider.p1.apiKey",
    )

    private val dialog = listOf(
        ChatMessage(ChatRole.SYSTEM, "Ты ассистент"),
        ChatMessage(ChatRole.USER, "Поставь будильник на 7"),
        ChatMessage(ChatRole.ASSISTANT, "Готово"),
        ChatMessage(ChatRole.USER, "А на 8?"),
    )

    private fun sessionIdFor(messages: List<ChatMessage>): String =
        OpenCodeZen.sessionId(dialogKey(messages))

    @Test
    fun fetchModels_sendsZenHeaders_andBearer() = runTest {
        val captured = mutableMapOf<String, String>()
        val engine = MockEngine { request ->
            request.headers.entries()
                .filter { it.key.lowercase().startsWith("x-opencode") || it.key.lowercase() == "authorization" }
                .forEach { captured[it.key.lowercase()] = it.value.single() }
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, zenProfile, secretsWithKey("test-key"), mockClient(engine))

        client.fetchModels()

        assertEquals("Bearer test-key", captured["authorization"])
        assertNotNull(captured["x-opencode-session"])
        val request = captured["x-opencode-request"]
        assertNotNull(request)
        assertTrue("bad request id: $request", request!!.startsWith("msg_"))
        runCatching { UUID.fromString(request.removePrefix("msg_")) }.getOrNull()
            ?: throw AssertionError("request id is not a UUID: $request")
    }

    @Test
    fun fetchModels_requestId_isFreshPerRequest() = runTest {
        val requestIds = mutableListOf<String>()
        val engine = MockEngine { request ->
            request.headers["x-opencode-request"]?.let { requestIds += it }
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val client = ModelClient.create(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, zenProfile, emptySecrets, mockClient(engine))

        client.fetchModels()
        client.fetchModels()

        assertEquals(2, requestIds.size)
        assertNotEquals(requestIds[0], requestIds[1])
    }

    @Test
    fun sessionId_isStable_withinSameDialog() {
        val whole = sessionIdFor(dialog)
        val beforeLastTurn = sessionIdFor(dialog.dropLast(2))
        val afterFirstTurn = sessionIdFor(dialog.take(2))

        assertEquals(whole, beforeLastTurn)
        assertEquals(whole, afterFirstTurn)
    }

    @Test
    fun sessionId_differs_forDifferentDialogHeads() {
        val a = sessionIdFor(listOf(ChatMessage(ChatRole.USER, "Привет")))
        val b = sessionIdFor(listOf(ChatMessage(ChatRole.USER, "Открой настройки")))

        assertNotEquals(a, b)
    }

    @Test
    fun sessionId_ignoresAssistantTurns() {
        val a = sessionIdFor(dialog)
        val b = sessionIdFor(dialog + ChatMessage(ChatRole.ASSISTANT, "Будильник на 8 поставлен"))

        assertEquals(a, b)
    }

    @Test
    fun classicFlavor_sendsNoZenHeaders() = runTest {
        val captured = mutableListOf<String>()
        val engine = MockEngine { request ->
            captured += request.headers.names().map { it.lowercase() }
            respond("""{"data":[]}""", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val profile = zenProfile.copy(preset = ProviderPreset.CUSTOM, protocol = ModelProtocol.OPENAI_COMPLETIONS)
        val client = OpenAiCompatibleClient.fromProfile(ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics, profile, emptySecrets, mockClient(engine))

        client.fetchModels()

        assertTrue(captured.none { it.startsWith("x-opencode") })
    }
}
