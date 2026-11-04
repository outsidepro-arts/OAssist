package ru.outsideproarts.oassist.core.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes in the same directory so a failed write cannot truncate the previous version. */
internal fun atomicWrite(file: File, write: (FileOutputStream) -> Unit) {
    val temporary = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { output ->
            write(output)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

internal fun atomicWriteText(file: File, text: String) =
    atomicWrite(file) { it.write(text.toByteArray(Charsets.UTF_8)) }
