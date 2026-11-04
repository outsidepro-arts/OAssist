package ru.outsideproarts.oassist.update

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A missing ledger is a first run; an unreadable or malformed ledger is not. */
internal class UpdateCacheOwnership(private val path: File) {
    private val atomic = AtomicFile(path)

    fun read(): Map<String, *> {
        if (!path.exists() && !File("${path.path}.bak").exists() && !File("${path.path}.new").exists()) return emptyMap<String, Any>()
        val bytes = atomic.readFully()
        check(bytes.size <= MAX_BYTES)
        val objectValue = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("Invalid ledger")
        check(objectValue.keys == setOf(UpdateCacheMaintenance.MARKER, UpdateCacheMaintenance.NAMES))
        val marker = objectValue[UpdateCacheMaintenance.MARKER] as? JsonPrimitive
        check(marker != null && !marker.isString && marker.booleanOrNull == true)
        val names = objectValue[UpdateCacheMaintenance.NAMES] as? JsonArray ?: error("Invalid ledger")
        val strings = names.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("Invalid name") }
        check(strings.toSet().size == strings.size)
        return mapOf(UpdateCacheMaintenance.MARKER to true, UpdateCacheMaintenance.NAMES to strings.toSet())
    }

    fun write(names: Set<String>): Boolean {
        val payload = JsonObject(mapOf(
            UpdateCacheMaintenance.MARKER to JsonPrimitive(true),
            UpdateCacheMaintenance.NAMES to JsonArray(names.sorted().map(::JsonPrimitive)),
        )).toString().toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_BYTES) return false
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(payload)
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
            read()[UpdateCacheMaintenance.NAMES] == names
        } catch (_: Exception) {
            stream?.let { atomic.failWrite(it) }
            false
        }
    }

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}
