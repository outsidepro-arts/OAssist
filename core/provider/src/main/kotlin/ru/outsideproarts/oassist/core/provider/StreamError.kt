package ru.outsideproarts.oassist.core.provider

import kotlinx.serialization.Serializable

/**
 * Ошибка API тем же SSE-потоком другим JSON-объектом
 * (`data: {"error": {"message": ...}}` — перегрузка, квоты, ключ).
 * Форма общая для OpenAI-совместимых API и Gemini.
 */
@Serializable
internal data class StreamErrorDto(
    val error: StreamErrorBodyDto? = null,
)

@Serializable
internal data class StreamErrorBodyDto(
    val message: String? = null,
)

/** Кап текста серверной ошибки из потока (как у Anthropic). */
internal const val STREAM_ERROR_LIMIT = 200

/**
 * Текст серверной ошибки из `data:`-строки SSE-потока, null — не ошибка.
 * Pure: тестируется без HTTP (MockEngine не умеет SSE — см. OpenAiStreamTest).
 *
 * Нюанс: `{"error":...}` декодируется и как обычный чанк (все поля с дефолтами),
 * поэтому клиенты зовут это только для строк без payload (пустой choices/candidates).
 */
internal fun detectStreamErrorMessage(data: String): String? =
    runCatching { ProviderJson.decodeFromString(StreamErrorDto.serializer(), data) }
        .getOrNull()?.error?.message
        ?.takeIf { it.isNotBlank() }
        ?.take(STREAM_ERROR_LIMIT)
