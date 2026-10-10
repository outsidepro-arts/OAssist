package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.openai.OpenAiModelsResponseDto

class ProviderErrorMappingTest {

    @Test
    fun openAiModelsResponse_withUnknownFields_parses() {
        val dto = ProviderJson.decodeFromString(
            OpenAiModelsResponseDto.serializer(),
            """
            {
              "object": "list",
              "data": [
                {"id": "mimo-v2.6-pro", "object": "model", "created": 1790931876, "owned_by": "opencode"},
                {"id": "glm-5.3", "object": "model", "created": 1790931876, "owned_by": "opencode"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(listOf("mimo-v2.6-pro", "glm-5.3"), dto.data.map { it.id })
    }

    @Test
    fun from_providerError_isIdentity() {
        val original = ProviderError.Protocol("bad")
        assertEquals(original, ProviderError.from(original))
    }

    @Test
    fun from_ioException_mapsToNetwork() {
        val e = ProviderError.from(java.net.SocketTimeoutException("timeout"))
        assertTrue(e is ProviderError.Network)
    }

    @Test
    fun from_serializationException_mapsToProtocol() {
        val e = ProviderError.from(kotlinx.serialization.SerializationException("bad json"))
        assertTrue(e is ProviderError.Protocol)
    }

    @Test
    fun from_unknownException_mapsToNetworkWithMessage() {
        val e = ProviderError.from(IllegalStateException("boom"))
        assertTrue(e is ProviderError.Network)
        assertEquals("boom", e.message)
    }

    @Test
    fun from_sseWrappedHttp_mapsToHttp() = runTest {
        // Ktor заворачивает ошибки SSE-стрима в SSEClientException:
        // статус 503 не должен теряться в общей «сетевой ошибке».
        val engine = MockEngine { respond("busy", status = HttpStatusCode.ServiceUnavailable) }
        val http = HttpClient(engine) { expectSuccess = true }
        val cause = runCatching { http.get("/") }.exceptionOrNull()
        assertTrue(cause is ResponseException)
        val wrapped = SSEClientException((cause as ResponseException).response, cause, "sse failed")
        val mapped = ProviderError.from(wrapped)
        assertTrue(mapped is ProviderError.Http)
        assertEquals(503, (mapped as ProviderError.Http).statusCode)
    }

    @Test
    fun from_sseEofWithIoCause_mapsToNetwork() = runTest {
        // Обрыв посреди стрима (200 + причина): честный Network, не Http.
        val engine = MockEngine { respond("data: {}\n\n") }
        val http = HttpClient(engine)
        val response = http.get("/")
        val wrapped = SSEClientException(response, java.io.IOException("stall"), "eof")
        assertTrue(ProviderError.from(wrapped) is ProviderError.Network)
    }

    @Test
    fun from_nestedCancellation_rethrown() {
        val inner = CancellationException("stop")
        val e = runCatching { ProviderError.from(RuntimeException(inner)) }.exceptionOrNull()
        assertTrue(e is CancellationException)
    }
}
