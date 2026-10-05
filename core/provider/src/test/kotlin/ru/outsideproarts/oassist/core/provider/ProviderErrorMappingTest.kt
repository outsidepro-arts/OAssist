package ru.outsideproarts.oassist.core.provider

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
}
