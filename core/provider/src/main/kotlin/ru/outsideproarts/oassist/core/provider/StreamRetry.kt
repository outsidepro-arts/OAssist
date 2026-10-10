package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Повтор стрима модели при «раннем» обрыве — исключении до первого чанка.
 *
 * Безопасен по построению: ни токенов, ни вызовов инструментов в этом раунде
 * ещё не было (повтор шлёт ту же историю заново). После первого чанка повтора
 * нет — иначе задвоились бы ответ, озвучка и биллинг.
 *
 * Ретраятся только сеть и 5xx/429/408: 401/протокол/конфигурация
 * детерминированы, повтор бессмысленен.
 *
 * @param resubscribe построение стрима заново (та же история/инструменты раунда).
 */
fun Flow<ChatChunk>.retryEarlyFailure(
    maxAttempts: Int = MAX_STREAM_ATTEMPTS,
    delayMs: Long = STREAM_RETRY_DELAY_MS,
    resubscribe: () -> Flow<ChatChunk>,
): Flow<ChatChunk> = flow {
    var attempt = 0
    while (true) {
        var emitted = false
        try {
            (if (attempt == 0) this@retryEarlyFailure else resubscribe()).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            attempt++
            if (emitted || attempt >= maxAttempts || !e.isEarlyRetryable()) throw e
            delay(delayMs)
        }
    }
}

/** Попыток стрима с учётом первой (1 повтор). */
const val MAX_STREAM_ATTEMPTS = 2

/** Пауза перед повтором стрима. */
const val STREAM_RETRY_DELAY_MS = 1500L

/**
 * Ошибка «ранняя» и ретраибельная: сеть или 5xx/429/408. 401/протокол/
 * конфигурация детерминированы — повтор бессмысленен. Используется и
 * [retryEarlyFailure], и UI для авто-повтора фронтом сети.
 */
fun Throwable.isEarlyRetryable(): Boolean = when (val err = ProviderError.from(this)) {
    // from() здесь не бросает: отмена отсечена вызывающими раньше.
    is ProviderError.Network -> true
    is ProviderError.Http -> err.statusCode in 500..599 ||
        err.statusCode == 408 || err.statusCode == 429
    else -> false
}
