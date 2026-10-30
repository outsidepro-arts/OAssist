package ru.outsideproarts.oassist.core.data.chat

import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.ImagePart
import java.io.File
import java.io.IOException
import java.util.Base64

/** Ref на медиа-файл диалога: путь относительно корня [ChatMediaStore] + метаданные. */
data class MediaRef(
    val path: String,
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int = 0,
)

/**
 * Файловое хранилище медиа диалогов: base64 аудио/картинок живут отдельными
 * файлами (`filesDir/chats/<chatId>/...`), в БД — только ref'ы. SQLite остаётся
 * лёгким; удаление чата/полное стирание сносит папки целиком.
 * Корень — `File`, чтобы хранилище тестировалось на JVM без Android.
 */
class ChatMediaStore(private val root: File) {

    fun writeAudio(chatId: String, turnId: String, part: AudioPart): MediaRef? {
        val data = decodeBase64(part.wavBase64) ?: return null
        val path = write(chatId, "${turnId}_a${extFor(part.mimeType)}", data) ?: return null
        return MediaRef(path, part.mimeType, durationSec = part.durationSec)
    }

    fun writeImage(chatId: String, turnId: String, name: String, part: ImagePart): MediaRef? {
        val data = decodeBase64(part.imageBase64) ?: return null
        val path = write(chatId, "${turnId}_$name${extFor(part.mimeType)}", data) ?: return null
        return MediaRef(path, part.mimeType, width = part.width, height = part.height)
    }

    fun readAudio(ref: MediaRef): AudioPart? {
        val data = readBytes(ref.path) ?: return null
        return AudioPart(
            wavBase64 = Base64.getEncoder().encodeToString(data),
            mimeType = ref.mimeType,
            durationSec = ref.durationSec,
        )
    }

    fun readImage(ref: MediaRef): ImagePart? {
        val data = readBytes(ref.path) ?: return null
        return ImagePart(
            imageBase64 = Base64.getEncoder().encodeToString(data),
            mimeType = ref.mimeType,
            width = ref.width,
            height = ref.height,
        )
    }

    fun deleteChat(chatId: String) {
        deleteRecursivelyOrThrow(File(root, chatId))
    }

    internal fun deleteTurn(chatId: String, turnId: String) {
        val dir = File(root, chatId)
        if (!dir.exists()) return
        val files = dir.listFiles() ?: throw IOException("Cannot list chat media directory: $dir")
        files.filter { it.name.startsWith("${turnId}_") }.forEach {
            if (!it.delete()) throw IOException("Cannot delete chat media: $it")
        }
        val remaining = dir.listFiles() ?: throw IOException("Cannot list chat media directory: $dir")
        if (remaining.isEmpty() && !dir.delete()) throw IOException("Cannot delete chat media directory: $dir")
    }

    fun deleteAll() {
        deleteRecursivelyOrThrow(root)
    }

    private fun deleteRecursivelyOrThrow(path: File) {
        if (!path.deleteRecursively()) throw IOException("Cannot delete chat media: $path")
    }

    private fun write(chatId: String, name: String, data: ByteArray): String? = runCatching {
        val dir = File(root, chatId)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        File(dir, name).writeBytes(data)
        "$chatId/$name"
    }.getOrNull()

    private fun readBytes(relative: String): ByteArray? {
        val file = File(root, relative)
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonicalFile.path.startsWith(canonicalRoot.path)) return null
        if (!canonicalFile.isFile) return null
        return runCatching { canonicalFile.readBytes() }.getOrNull()
    }

    private fun decodeBase64(base64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(base64) }.getOrNull()

    private fun extFor(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "audio/wav", "audio/x-wav" -> ".wav"
        "audio/mpeg" -> ".mp3"
        "audio/ogg" -> ".ogg"
        else -> ".bin"
    }
}
