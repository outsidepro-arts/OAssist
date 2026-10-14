package ru.outsideproarts.oassist.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Классификация ошибок по реальным телам OpenCode Zen/Go (диагностические пробы
 * 2026-10-14): free-tier, гео-блок, снятая модель, неверный протокол, выключенный
 * доступ, недоступный апстрим.
 */
class ProviderErrorClassificationTest {

    private val freeTierBody =
        """{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode. Download it at https://opencode.ai and keep building."}}"""

    private val regionBody =
        """{"model":"gpt-5.5","error":{"code":"unsupported_country_region_territory","message":"Country, region, or territory not supported","param":null,"type":"request_forbidden"}}"""

    private val deprecatedBody =
        """{"type":"error","error":{"type":"ModelDeprecated","message":"Model mimo-v2.5-free has been deprecated. Use mimo-v2.6-flash-free instead."},"metadata":{"model":"mimo-v2.5-free","replacement":"mimo-v2.6-flash-free"}}"""

    private val protocolBody =
        """{"type":"error","error":{"type":"ModelProtocolUnsupported","message":"Model does not support this protocol."}}"""

    private val accessBody =
        """{"type":"error","error":{"type":"api_error","message":"Upstream request failed: Model access is disabled"}}"""

    private val upstreamBody =
        """{"error":{"type":"server_error","message":"Upstream request failed: Endpoint is unavailable."}}"""

    @Test
    fun freeTier_recognized() {
        assertEquals(ProviderErrorKind.FREE_TIER, classifyProviderError(403, freeTierBody))
        assertEquals(ProviderErrorKind.FREE_TIER, ProviderError.Http(403, freeTierBody).kind())
    }

    @Test
    fun regionBlocked_recognized() {
        assertEquals(ProviderErrorKind.REGION_BLOCKED, classifyProviderError(403, regionBody))
    }

    @Test
    fun deprecated_recognized_withReplacement() {
        assertEquals(ProviderErrorKind.MODEL_DEPRECATED, classifyProviderError(410, deprecatedBody))
        assertEquals("mimo-v2.6-flash-free", extractReplacement(deprecatedBody))
    }

    @Test
    fun deprecated_fallbackText_replacement() {
        val body = """{"error":{"type":"ModelDeprecated","message":"Model old-name has been deprecated. Use new-name instead."}}"""
        assertEquals(ProviderErrorKind.MODEL_DEPRECATED, classifyProviderError(410, body))
        assertEquals("new-name", extractReplacement(body))
        assertNull(extractReplacement("""{"error":{"message":"gone"}}"""))
    }

    @Test
    fun protocolUnsupported_recognized() {
        assertEquals(ProviderErrorKind.MODEL_PROTOCOL_UNSUPPORTED, classifyProviderError(400, protocolBody))
        assertTrue(isProtocolUnsupportedMessage(protocolBody))
        assertFalse(isProtocolUnsupportedMessage(accessBody))
    }

    @Test
    fun modelAccess_winsOverUpstream() {
        assertEquals(ProviderErrorKind.MODEL_ACCESS, classifyProviderError(403, accessBody))
    }

    @Test
    fun upstreamUnavailable_recognized() {
        assertEquals(ProviderErrorKind.UPSTREAM_UNAVAILABLE, classifyProviderError(403, upstreamBody))
    }

    @Test
    fun plainAuth_andStatusFallback() {
        assertEquals(ProviderErrorKind.AUTH, classifyProviderError(401, "Missing API key."))
        assertEquals(ProviderErrorKind.AUTH, classifyProviderError(403, null))
        assertEquals(ProviderErrorKind.NOT_FOUND, classifyProviderError(404, "nope"))
        assertEquals(ProviderErrorKind.HTTP, classifyProviderError(500, "boom"))
    }

    @Test
    fun protocolError_withMarkers_classifiedByText() {
        val inStream = ProviderError.Protocol("openai: Model does not support this protocol.")
        assertEquals(ProviderErrorKind.MODEL_PROTOCOL_UNSUPPORTED, inStream.kind())

        val plain = ProviderError.Protocol("openai: server is overloaded")
        assertEquals(ProviderErrorKind.PROTOCOL, plain.kind())
    }

    @Test
    fun isProtocolUnsupported_walksCauseChain() {
        val wrapped = RuntimeException(IllegalStateException("wrapper", ProviderError.Http(400, protocolBody)))
        assertTrue(isProtocolUnsupported(wrapped))
        assertFalse(isProtocolUnsupported(RuntimeException("plain")))
    }
}
