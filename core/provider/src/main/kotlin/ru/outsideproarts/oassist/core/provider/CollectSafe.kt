package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Сбор потока агента без падения вызывающего: исключения (сеть, HTTP, не поддержанная
 * модель, неправильный формат ответа) превращаются в [ProviderError] и возвращаются,
 * а не роняют корутину. [CancellationException] пробрасывается как есть.
 *
 * Возвращает `null` при штатном завершении потока.
 */
suspend fun Flow<ChatChunk>.collectSafe(
    onChunk: (ChatChunk) -> Unit,
): ProviderError? = try {
    collect { onChunk(it) }
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ProviderError.from(e)
}
