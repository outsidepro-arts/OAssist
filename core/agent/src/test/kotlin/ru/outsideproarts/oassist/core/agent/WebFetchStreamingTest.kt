package ru.outsideproarts.oassist.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchStreamingTest {
    @Test
    fun staticAndMarkdownStopReadingAnUnendingBody() = runBlocking {
        for (markdown in listOf(false, true)) {
            val channel = ByteChannel(autoFlush = true)
            val producer = launch {
                channel.writeFully(ByteArray(WebContentActions.MAX_BODY_BYTES + 1) { 'x'.code.toByte() })
                awaitCancellation()
            }
            val client = HttpClient(MockEngine {
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }) { expectSuccess = true }
            try {
                val actions = WebContentActions(client)
                val page = withTimeout(5000) {
                    if (markdown) actions.fetchMarkdownVariant("https://example.com/page").getOrThrow()
                    else actions.fetchStatic("https://example.com/page").getOrThrow()
                }
                assertEquals(WebContentActions.MAX_BODY_BYTES, page.body.length)
                assertTrue(page.bodyTruncated)
                assertTrue(channel.isClosedForWrite)
            } finally { client.close(); producer.cancelAndJoin() }
        }
    }

    @Test
    fun oversizedDeclaredLengthClosesWithoutDraining() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine {
            respond(channel, HttpStatusCode.OK, headersOf(
                HttpHeaders.ContentLength, (WebContentActions.MAX_BODY_BYTES * 9).toString()
            ))
        }) { expectSuccess = true }
        try {
            val result = withTimeout(5000) { WebContentActions(client).fetchStatic("https://example.com/") }
            assertTrue(result.isFailure)
            assertTrue(channel.isClosedForWrite)
        } finally { client.close() }
    }

    @Test
    fun errorBodyIsBoundedEvenWithExpectSuccess() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val producer = launch {
            channel.writeFully(ByteArray(WebContentActions.MAX_BODY_BYTES + 1) { 'e'.code.toByte() })
            awaitCancellation()
        }
        val client = HttpClient(MockEngine {
            respond(channel, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "text/plain"))
        }) { expectSuccess = true }
        try {
            val result = withTimeout(5000) { WebContentActions(client).fetchStatic("https://example.com/") }
            assertTrue(result.exceptionOrNull() is ClientRequestException)
            assertTrue(channel.isClosedForWrite)
        } finally { client.close(); producer.cancelAndJoin() }
    }

    @Test
    fun retryClosesDiscardedBodyBeforeNextAttempt() = runBlocking {
        val discarded = ByteChannel(autoFlush = true)
        var calls = 0
        var closedBeforeRetry = false
        val client = HttpClient(MockEngine {
            calls++
            if (calls == 1) {
                respond(discarded, HttpStatusCode.InternalServerError)
            } else {
                closedBeforeRetry = discarded.isClosedForWrite
                respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
        }) {
            install(HttpRequestRetry) {
                retryIf(maxRetries = 1) { _, response -> response.status.value >= 500 }
                delayMillis { 0 }
            }
            expectSuccess = true
        }
        try {
            val page = withTimeout(5000) { WebContentActions(client).fetchStatic("https://example.com/").getOrThrow() }
            assertEquals("ok", page.body)
            assertEquals(2, calls)
            assertTrue(closedBeforeRetry)
        } finally { client.close() }
    }

    @Test
    fun cancellationClosesPendingBody() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val reading = CompletableDeferred<Unit>()
        val observed = object : ByteReadChannel by channel {
            override suspend fun awaitContent(min: Int): Boolean {
                reading.complete(Unit)
                return channel.awaitContent(min)
            }
        }
        val client = HttpClient(MockEngine {
            respond(observed, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        try {
            val request = async { WebContentActions(client).fetchStatic("https://example.com/") }
            withTimeout(5000) { reading.await() }
            request.cancelAndJoin()
            assertTrue(channel.isClosedForWrite)
        } finally { client.close() }
    }

    @Test
    fun completeBodyUsesDeclaredCharsetAndIsNotTruncated() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(byteArrayOf(0xe9.toByte()), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/plain; charset=ISO-8859-1"))
        })
        try {
            val page = WebContentActions(client).fetchStatic("https://example.com/").getOrThrow()
            assertEquals("é", page.body)
            assertTrue(!page.bodyTruncated)
        } finally { client.close() }
    }

    @Test
    fun declaredLengthAtAndAboveLimitPreservesTruncationFlag() = runBlocking {
        for (extra in 0..1) {
            val size = WebContentActions.MAX_BODY_BYTES + extra
            val client = HttpClient(MockEngine {
                respond(ByteArray(size) { 'x'.code.toByte() }, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, size.toString()))
            })
            try {
                val page = WebContentActions(client).fetchStatic("https://example.com/").getOrThrow()
                assertEquals(WebContentActions.MAX_BODY_BYTES, page.body.length)
                assertEquals(extra == 1, page.bodyTruncated)
            } finally { client.close() }
        }
    }
}
