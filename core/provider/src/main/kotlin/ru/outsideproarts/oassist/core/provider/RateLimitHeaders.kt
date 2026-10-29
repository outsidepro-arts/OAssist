package ru.outsideproarts.oassist.core.provider

import io.ktor.http.Headers
import ru.outsideproarts.oassist.core.model.RateLimitBucket
import ru.outsideproarts.oassist.core.model.RateLimitSnapshot
import ru.outsideproarts.oassist.core.model.ProviderUsage

/**
 * Лимиты провайдера из HTTP-заголовков ответа («сколько осталось до
 * ограничения»): OpenAI `x-ratelimit-{limit,remaining,reset}-{requests,tokens}`,
 * Anthropic `anthropic-ratelimit-{requests,input-tokens,output-tokens,tokens}-*`
 * и generic `x-ratelimit-{limit,remaining,reset}` (шлюзы вроде Envoy).
 * Мульти-политика (`200, 200;w=60, ...`) читается по первому значению.
 * Сброс парсится lenient: RFC3339 (Anthropic) → epoch, unix-секунды либо
 * задержка в секундах (IETF draft) → now + задержка, длительность `6m0s`
 * (OpenAI) → now + длительность. Нераспознанное — null, ничего не выдумываем.
 */
internal object RateLimitHeaders {

    fun parse(
        headers: Headers,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): RateLimitSnapshot? {
        val requests = bucket(
            limit = headers["x-ratelimit-limit-requests"]
                ?: headers["anthropic-ratelimit-requests-limit"]
                ?: headers["x-ratelimit-limit"],
            remaining = headers["x-ratelimit-remaining-requests"]
                ?: headers["anthropic-ratelimit-requests-remaining"]
                ?: headers["x-ratelimit-remaining"],
            reset = headers["x-ratelimit-reset-requests"]
                ?: headers["anthropic-ratelimit-requests-reset"]
                ?: headers["x-ratelimit-reset"],
            nowEpochSeconds = nowEpochSeconds,
        )
        val tokens = bucket(
            limit = headers["x-ratelimit-limit-tokens"]
                ?: headers["anthropic-ratelimit-tokens-limit"],
            remaining = headers["x-ratelimit-remaining-tokens"]
                ?: headers["anthropic-ratelimit-tokens-remaining"],
            reset = headers["x-ratelimit-reset-tokens"]
                ?: headers["anthropic-ratelimit-tokens-reset"],
            nowEpochSeconds = nowEpochSeconds,
        )
        val inputTokens = bucket(
            limit = headers["anthropic-ratelimit-input-tokens-limit"],
            remaining = headers["anthropic-ratelimit-input-tokens-remaining"],
            reset = headers["anthropic-ratelimit-input-tokens-reset"],
            nowEpochSeconds = nowEpochSeconds,
        )
        val outputTokens = bucket(
            limit = headers["anthropic-ratelimit-output-tokens-limit"],
            remaining = headers["anthropic-ratelimit-output-tokens-remaining"],
            reset = headers["anthropic-ratelimit-output-tokens-reset"],
            nowEpochSeconds = nowEpochSeconds,
        )
        val snapshot = RateLimitSnapshot(
            requests = requests,
            tokens = tokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
        return snapshot.takeUnless { it.isEmpty }
    }

    private fun bucket(
        limit: String?,
        remaining: String?,
        reset: String?,
        nowEpochSeconds: Long,
    ): RateLimitBucket? {
        val parsedLimit = parseCount(limit)
        val parsedRemaining = parseCount(remaining)
        val resetAt = parseReset(reset, nowEpochSeconds)
        if (parsedLimit == null && parsedRemaining == null && resetAt == null) return null
        return RateLimitBucket(
            limit = parsedLimit,
            remaining = parsedRemaining,
            resetAtEpochSeconds = resetAt,
        )
    }

    /** Мульти-политика (`200, 200;w=60`) — первое значение. */
    private fun parseCount(value: String?): Long? =
        value?.substringBefore(',')?.substringBefore(';')?.trim()?.toLongOrNull()

    private fun parseReset(value: String?, nowEpochSeconds: Long): Long? {
        val raw = value?.substringBefore(',')?.substringBefore(';')?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.contains('T')) {
            return runCatching { java.time.Instant.parse(raw).epochSecond }
                .getOrElse { runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull() }
        }
        raw.toLongOrNull()?.let { n ->
            return if (n >= EPOCH_SECONDS_FLOOR) n else nowEpochSeconds + n
        }
        val match = DURATION_REGEX.matchEntire(raw) ?: return null
        val (hours, minutes, seconds) = match.destructured
        return nowEpochSeconds +
            hours.orZero() * 3600 +
            minutes.orZero() * 60 +
            seconds.orZero()
    }

    private fun String.orZero(): Long = if (isEmpty()) 0L else toLongOrNull() ?: 0L

    /** Ниже — задержка в секундах, не unix-время (сбросы обычно < суток). */
    private const val EPOCH_SECONDS_FLOOR = 1_000_000_000L

    private val DURATION_REGEX = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""")
}

/**
 * Приклеить срез лимитов к usage round-trip'а: снапшот переживает отсутствие
 * токенов (usage без статистики, но с лимитами — не «пустой»).
 */
internal fun ProviderUsage?.withRateLimits(snapshot: RateLimitSnapshot?): ProviderUsage? {
    if (snapshot == null || snapshot.isEmpty) return this
    return (this ?: ProviderUsage()).copy(rateLimits = snapshot)
}
