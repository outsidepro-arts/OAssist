package ru.outsideproarts.oassist.update

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Runs before update state becomes available; protected names remain protected across restarts. */
internal class UpdateCacheMaintenance(
    private val directory: File,
    private val read: () -> Map<String, *>,
    private val write: (Set<String>) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var ready = false

    @Synchronized fun run() {
        val files = directory.listFiles() ?: return
        files.filter { ordinary(it) && PART.matches(it.name) }.forEach { it.delete() }
        val metadata = metadata() ?: return
        if (!metadata.initialized) {
            val legacy = files.filter { ordinary(it) && APK.matches(it.name) }.map { it.name }.toSet()
            if (!write(legacy)) return
        }
        val protected = metadata()?.takeIf { it.initialized }?.names ?: return
        ready = true
        val cutoff = now() - RETENTION_MILLIS
        files.filter { ordinary(it) && APK.matches(it.name) && it.name !in protected &&
            it.lastModified() > 0 && it.lastModified() <= cutoff }.forEach { it.delete() }
    }

    @Synchronized fun mayReplace(name: String): Boolean = ready && metadata()?.let { it.initialized && name !in it.names } == true

    @Synchronized fun commitPart(part: File, target: File) {
        check(part.parentFile?.canonicalFile == directory.canonicalFile && target.parentFile?.canonicalFile == directory.canonicalFile)
        check(APK.matches(target.name) && PART.matches(part.name) && part.name.startsWith(".${target.name}.") && ordinary(part))
        check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) || ordinary(target))
        check(mayReplace(target.name))
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun protect(name: String): Boolean {
        if (!ready || !APK.matches(name)) return false
        val current = metadata()?.takeIf { it.initialized } ?: return false
        if (name in current.names) return true
        val saved = try { write(current.names + name) } catch (_: Exception) { false }
        if (!saved) ready = false
        return saved
    }

    private fun metadata(): Metadata? = try {
        val values = read()
        val marker = values[MARKER]
        val raw = values[NAMES]
        if (marker == null && raw == null) Metadata(false, emptySet())
        else if (marker == true && raw is Set<*> && raw.all { it is String && APK.matches(it) })
            Metadata(true, raw.filterIsInstance<String>().toSet())
        else null
    } catch (_: Exception) { null }

    private fun ordinary(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private data class Metadata(val initialized: Boolean, val names: Set<String>)

    companion object {
        const val MARKER = "legacy_protected"
        const val NAMES = "protected_apks"
        private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val APK = Regex("[A-Za-z0-9._-]+\\.apk")
        private val PART = Regex("\\.[A-Za-z0-9._-]+\\.apk\\.[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.part")
    }
}
