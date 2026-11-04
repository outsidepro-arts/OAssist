package ru.outsideproarts.oassist.core.data.memory

import android.content.Context
import ru.outsideproarts.oassist.core.data.atomicWriteText
import java.io.File
import java.io.IOException
import java.nio.file.Files

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

class FileMemoryStore private constructor(
    private val directory: () -> File,
    private val deleteFile: (File) -> Boolean,
    private val writeFile: (File, String) -> Unit,
) : MemoryStore {

    constructor(deviceContext: Context) : this({ deviceContext.filesDir }, File::delete, ::atomicWriteText)

    internal constructor(directory: File, deleteFile: (File) -> Boolean = File::delete, writeFile: (File, String) -> Unit = ::atomicWriteText) :
        this({ directory }, deleteFile, writeFile)

    private fun file(): File {
        val dir = directory()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun readLocked(): String {
        val f = file()
        return if (Files.notExists(f.toPath())) "" else f.readText().trim()
    }

    override suspend fun read(): String = synchronized(lock) { readLocked() }

    override suspend fun write(text: String) {
        synchronized(lock) { writeFile(file(), text.trim() + "\n") }
    }

    override suspend fun append(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            val current = readLocked()
            writeFile(file(), (if (current.isEmpty()) trimmed else "$current\n$trimmed") + "\n")
        }
    }

    override suspend fun clear() {
        synchronized(lock) {
            val target = file()
            if (!Files.notExists(target.toPath()) && !deleteFile(target) && !Files.notExists(target.toPath())) {
                throw IOException("Cannot delete memory")
            }
        }
    }

    companion object {
        private val lock = Any()
        /** Кап памяти в системном промпте (см. PromptRenderer). */
        const val MAX_PROMPT_CHARS = 8000
    }
}
