package ru.outsideproarts.oassist.core.provider

import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.RateLimitSnapshot

class RateLimitHeadersTest {

    private val now = 1_760_000_000L

    @Test
    fun openAiHeaders_parseRequestsAndTokensBuckets() {
        val headers = headersOf(
            "x-ratelimit-limit-requests" to listOf("5000"),
            "x-ratelimit-remaining-requests" to listOf("4999"),
            "x-ratelimit-reset-requests" to listOf("6m0s"),
            "x-ratelimit-limit-tokens" to listOf("2000000"),
            "x-ratelimit-remaining-tokens" to listOf("1500000"),
            "x-ratelimit-reset-tokens" to listOf("1h2m3s"),
        )
        val snapshot = RateLimitHeaders.parse(headers, now)!!
        assertEquals(5000L, snapshot.requests!!.limit)
        assertEquals(4999L, snapshot.requests!!.remaining)
        assertEquals(now + 360, snapshot.requests!!.resetAtEpochSeconds)
        assertEquals(2000000L, snapshot.tokens!!.limit)
        assertEquals(1500000L, snapshot.tokens!!.remaining)
        assertEquals(now + 3723, snapshot.tokens!!.resetAtEpochSeconds)
    }

    @Test
    fun anthropicHeaders_parseAllBuckets() {
        val headers = headersOf(
            "anthropic-ratelimit-requests-limit" to listOf("60"),
            "anthropic-ratelimit-requests-remaining" to listOf("59"),
            "anthropic-ratelimit-requests-reset" to listOf("2025-01-08T12:00:00Z"),
            "anthropic-ratelimit-input-tokens-limit" to listOf("100000"),
            "anthropic-ratelimit-input-tokens-remaining" to listOf("90000"),
            "anthropic-ratelimit-output-tokens-limit" to listOf("10000"),
            "anthropic-ratelimit-output-tokens-remaining" to listOf("9000"),
            "anthropic-ratelimit-tokens-limit" to listOf("110000"),
            "anthropic-ratelimit-tokens-remaining" to listOf("99000"),
        )
        val snapshot = RateLimitHeaders.parse(headers, now)!!
        assertEquals(60L, snapshot.requests!!.limit)
        assertEquals(59L, snapshot.requests!!.remaining)
        assertEquals(java.time.Instant.parse("2025-01-08T12:00:00Z").epochSecond, snapshot.requests!!.resetAtEpochSeconds)
        assertEquals(90000L, snapshot.inputTokens!!.remaining)
        assertEquals(9000L, snapshot.outputTokens!!.remaining)
        assertEquals(110000L, snapshot.tokens!!.limit)
    }

    @Test
    fun genericMultiPolicy_firstValueWins() {
        val headers = headersOf(
            "x-ratelimit-limit" to listOf("200, 200;w=60, 200000;w=60"),
            "x-ratelimit-remaining" to listOf("150"),
            "x-ratelimit-reset" to listOf("45"),
        )
        val snapshot = RateLimitHeaders.parse(headers, now)!!
        assertEquals(200L, snapshot.requests!!.limit)
        assertEquals(150L, snapshot.requests!!.remaining)
        // IETF draft: reset — задержка в секундах.
        assertEquals(now + 45, snapshot.requests!!.resetAtEpochSeconds)
    }

    @Test
    fun unixEpochReset_parsedAsTimestamp() {
        val headers = headersOf("x-ratelimit-reset-requests" to listOf("1760000100"))
        val snapshot = RateLimitHeaders.parse(headers, now)!!
        assertEquals(1760000100L, snapshot.requests!!.resetAtEpochSeconds)
    }

    @Test
    fun malformedValues_keepWhatIsReadable() {
        val headers = headersOf(
            "x-ratelimit-remaining-requests" to listOf("5"),
            "x-ratelimit-reset-requests" to listOf("soon"),
            "x-ratelimit-limit-tokens" to listOf("abc"),
        )
        val snapshot = RateLimitHeaders.parse(headers, now)!!
        assertEquals(5L, snapshot.requests!!.remaining)
        assertNull(snapshot.requests!!.resetAtEpochSeconds)
        assertNull(snapshot.tokens)
    }

    @Test
    fun noRateLimitHeaders_returnsNull() {
        assertNull(RateLimitHeaders.parse(headersOf("Content-Type" to listOf("application/json")), now))
    }

    @Test
    fun withRateLimits_attachesToUsageOrCreatesIt() {
        val snapshot = RateLimitSnapshot(requests = ru.outsideproarts.oassist.core.model.RateLimitBucket(remaining = 1))
        assertEquals(snapshot, ProviderUsage.of(inputTokens = 1).withRateLimits(snapshot)!!.rateLimits)
        assertEquals(snapshot, (null as ProviderUsage?).withRateLimits(snapshot)!!.rateLimits)
        assertNull((null as ProviderUsage?).withRateLimits(null))
    }
}
