package ru.outsideproarts.oassist.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Детектор серверных ошибок в SSE-потоке. Чистый тест без HTTP:
 * MockEngine не поддерживает SSECapability движка, поэтому ветвление
 * «пустой payload → проверка на error» в клиентах покрыто здесь,
 * а проводка takeIf(isNotEmpty) — ревью (две строки на клиент).
 */
class StreamErrorTest {

    @Test
    fun openAiErrorShape_yieldsMessage() {
        assertEquals(
            "overloaded",
            detectStreamErrorMessage("""{"error":{"message":"overloaded","type":"server_error"}}"""),
        )
    }

    @Test
    fun geminiErrorShape_yieldsMessage() {
        assertEquals(
            "quota exceeded",
            detectStreamErrorMessage("""{"error":{"message":"quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""),
        )
    }

    @Test
    fun normalChunk_yieldsNull() {
        assertNull(
            detectStreamErrorMessage(
                """{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""",
            ),
        )
    }

    @Test
    fun garbage_yieldsNull() {
        assertNull(detectStreamErrorMessage("not-json-{"))
        assertNull(detectStreamErrorMessage(""))
        assertNull(detectStreamErrorMessage("[DONE]"))
    }

    @Test
    fun errorWithoutMessage_yieldsNull() {
        assertNull(detectStreamErrorMessage("""{"error":{}}"""))
        assertNull(detectStreamErrorMessage("""{"error":{"message":"  "}}"""))
    }

    @Test
    fun longMessage_capped() {
        val long = "x".repeat(500)
        assertEquals(200, detectStreamErrorMessage("""{"error":{"message":"$long"}}""")?.length)
    }
}
