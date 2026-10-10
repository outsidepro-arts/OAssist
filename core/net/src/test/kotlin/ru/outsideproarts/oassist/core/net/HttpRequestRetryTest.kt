package ru.outsideproarts.oassist.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Поведение [installRetryPolicy] на MockEngine: тот же порядок конфигурации,
 * что и в проде (`expectSuccess = true` после установки политики) — тест
 * фиксирует, какие запросы реально повторяются, а какие нет.
 */
class HttpRequestRetryTest {

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine) {
        installRetryPolicy(maxDelayMs = 20, randomizationMs = 0)
        expectSuccess = true
    }

    @Test
    fun serverErrors_retriedUntilSuccess() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls <= 2) {
                respond("busy", status = HttpStatusCode.ServiceUnavailable)
            } else {
                respond("{}", status = HttpStatusCode.OK)
            }
        }
        val response = client(engine).get("https://x.test/models")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, calls)
    }

    @Test
    fun tooManyRequests_retried() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("slow down", status = HttpStatusCode.TooManyRequests)
            } else {
                respond("{}", status = HttpStatusCode.OK)
            }
        }
        val response = client(engine).get("https://x.test/models")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
    }

    @Test
    fun clientError_notRetried() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("bad", status = HttpStatusCode.BadRequest)
        }
        runCatching { client(engine).get("https://x.test/models") }
        assertEquals(1, calls)
    }

    @Test
    fun authError_notRetried() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("denied", status = HttpStatusCode.Unauthorized)
        }
        runCatching { client(engine).get("https://x.test/models") }
        assertEquals(1, calls)
    }

    @Test
    fun noAutoRetryAttribute_suppressesRetry() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("boom", status = HttpStatusCode.InternalServerError)
        }
        runCatching {
            client(engine).get("https://x.test/models") {
                attributes.put(NoAutoRetry, Unit)
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun networkException_retried() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) throw java.io.IOException("reset") else respond("{}", status = HttpStatusCode.OK)
        }
        val response = client(engine).get("https://x.test/models")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
    }
}
