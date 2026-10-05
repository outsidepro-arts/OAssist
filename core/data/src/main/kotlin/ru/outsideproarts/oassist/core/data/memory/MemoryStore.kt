package ru.outsideproarts.oassist.core.data.memory

import android.content.Context
import java.io.File

/**
 * Память агента: один `MEMORY.md`, факты пользователя.
 *
 * device-protected storage — ассистент читает с экрана блокировки.
 * Секреты сюда запрещены (ключи/токены — только в SecretStore):
 * UI показывает предупреждение, redacting-логгер режет по паттернам.
 * Интерфейс — для фейков в тестах тулов; реализация — файл.
 */
interface MemoryStore {
    suspend fun read(): String
    suspend fun write(text: String)
    suspend fun append(line: String)
    suspend fun clear()
}

private const val FILE_NAME = "MEMORY.md"

class FileMemoryStore(private val deviceContext: Context) : MemoryStore {

    private fun file(): File {
        val dir = deviceContext.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    override suspend fun read(): String =
        runCatching {
            val f = file()
            if (!f.exists()) "" else f.readText().trim()
        }.getOrElse { "" }

    override suspend fun write(text: String) {
        file().writeText(text.trim() + "\n")
    }

    override suspend fun append(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val current = read()
        write(if (current.isEmpty()) trimmed else "$current\n$trimmed")
    }

    override suspend fun clear() {
        runCatching { file().delete() }
    }

    companion object {
        /** Кап памяти в системном промпте (см. PromptRenderer). */
        const val MAX_PROMPT_CHARS = 8000
    }
}
