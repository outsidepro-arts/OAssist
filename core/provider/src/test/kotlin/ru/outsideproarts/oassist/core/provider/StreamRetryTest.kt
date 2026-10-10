package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRetryTest {

    @Test
    fun earlyNetworkError_retriesOnceAndSucceeds() = runTest {
        var resubscribes = 0
        val first: Flow<ChatChunk> = flow { throw java.io.IOException("reset") }
        val chunks = first.retryEarlyFailure(delayMs = 1) {
            resubscribes++
            flowOf(ChatChunk.Content("ok"), ChatChunk.Finish("stop"))
        }.toList()
        assertEquals(1, resubscribes)
        assertEquals(
            listOf(ChatChunk.Content("ok"), ChatChunk.Finish("stop")),
            chunks,
        )
    }

    @Test
    fun earlyHttp503_retries() = runTest {
        var resubscribes = 0
        val first: Flow<ChatChunk> = flow { throw ProviderError.Http(503, "busy") }
        first.retryEarlyFailure(delayMs = 1) {
            resubscribes++
            flowOf(ChatChunk.Finish("stop"))
        }.toList()
        assertEquals(1, resubscribes)
    }

    @Test
    fun errorAfterFirstChunk_noRetry() = runTest {
        var resubscribes = 0
        val first = flow {
            emit(ChatChunk.Content("part"))
            throw java.io.IOException("stall")
        }
        val e = runCatching {
            first.retryEarlyFailure(delayMs = 1) {
                resubscribes++
                flowOf()
            }.toList()
        }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        assertEquals(0, resubscribes)
    }

    @Test
    fun earlyHttp401_noRetry() = runTest {
        var resubscribes = 0
        val first: Flow<ChatChunk> = flow { throw ProviderError.Http(401, "bad key") }
        val e = runCatching {
            first.retryEarlyFailure(delayMs = 1) {
                resubscribes++
                flowOf()
            }.toList()
        }.exceptionOrNull()
        assertTrue(e is ProviderError.Http)
        assertEquals(0, resubscribes)
    }

    @Test
    fun earlyProtocol_noRetry() = runTest {
        var resubscribes = 0
        val first: Flow<ChatChunk> = flow { throw ProviderError.Protocol("bad format") }
        runCatching {
            first.retryEarlyFailure(delayMs = 1) {
                resubscribes++
                flowOf()
            }.toList()
        }.exceptionOrNull()
        assertEquals(0, resubscribes)
    }

    @Test
    fun cancellation_rethrownWithoutRetry() = runTest {
        var resubscribes = 0
        val first: Flow<ChatChunk> = flow { throw CancellationException("stop") }
        val e = runCatching {
            first.retryEarlyFailure(delayMs = 1) {
                resubscribes++
                flowOf()
            }.toList()
        }.exceptionOrNull()
        assertTrue(e is CancellationException)
        assertEquals(0, resubscribes)
    }

    @Test
    fun persistentFailure_throwsAfterMaxAttempts() = runTest {
        var resubscribes = 0
        val first: Flow<ChatChunk> = flow { throw java.io.IOException("down") }
        val e = runCatching {
            first.retryEarlyFailure(delayMs = 1) {
                resubscribes++
                flow { throw java.io.IOException("still down") }
            }.toList()
        }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        // Первая попытка + один повтор, дальше — наружу.
        assertEquals(1, resubscribes)
    }

    @Test
    fun isEarlyRetryable_matrix() {
        assertTrue(java.io.IOException("x").isEarlyRetryable())
        assertTrue(ProviderError.Network("x").isEarlyRetryable())
        assertTrue(ProviderError.Http(500, null).isEarlyRetryable())
        assertTrue(ProviderError.Http(503, null).isEarlyRetryable())
        assertTrue(ProviderError.Http(429, null).isEarlyRetryable())
        assertTrue(ProviderError.Http(408, null).isEarlyRetryable())
        assertFalse(ProviderError.Http(400, null).isEarlyRetryable())
        assertFalse(ProviderError.Http(401, null).isEarlyRetryable())
        assertFalse(ProviderError.Http(404, null).isEarlyRetryable())
        assertFalse(ProviderError.Protocol("x").isEarlyRetryable())
        assertFalse(
            ProviderError.NotConfigured(ProviderError.NotConfigured.Reason.PROVIDER_MISSING)
                .isEarlyRetryable(),
        )
    }
}
