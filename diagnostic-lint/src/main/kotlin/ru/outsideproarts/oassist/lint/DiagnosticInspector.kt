package ru.outsideproarts.oassist.lint

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun main(args: Array<String>) {
    require(args.isNotEmpty() && args.size <= 2) { "Usage: inspectDiagnostics <report.zip> [operation-id]" }
    val filter = args.getOrNull(1)
    val json = Json { ignoreUnknownKeys = true }
    ZipFile(File(args[0])).use { zip ->
        val names = mutableSetOf<String>()
        var totalBytes = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            require(names.add(entry.name)) { "Duplicate archive entry: ${entry.name}" }
            require(names.size <= 4096) { "Too many archive entries" }
            require(!entry.name.startsWith('/') && !entry.name.contains('\\') &&
                entry.name.split('/').none { it == ".." }) { "Invalid archive path: ${entry.name}" }
            require(entry.size in 0..268_435_456) { "Invalid archive entry size: ${entry.name}" }
            totalBytes += entry.size
            require(totalBytes <= 536_870_912) { "Archive expands beyond the size limit" }
            val crc = CRC32()
            var readBytes = 0L
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    readBytes += read
                    require(readBytes <= 268_435_456) { "Archive entry exceeds size limit" }
                    crc.update(buffer, 0, read)
                }
            }
            require(readBytes == entry.size && crc.value == entry.crc) { "Corrupt archive entry: ${entry.name}" }
        }
        val manifestEntry = requireNotNull(zip.getEntry("manifest.json")) { "Missing manifest.json" }
        require(manifestEntry.size <= 1_048_576) { "Manifest is too large" }
        val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        require(manifest["version"]?.jsonPrimitive?.content == "1") { "Unsupported diagnostic format" }
        println("Manifest: $manifest")
        val events = requireNotNull(zip.getEntry("events.jsonl")) { "Missing events.jsonl" }
        val starts = mutableSetOf<String>()
        val ends = mutableSetOf<String>()
        val pendingCalls = mutableMapOf<Pair<String, String>, Int>()
        val orphanResults = mutableListOf<Pair<String, String>>()
        var count = 0
        zip.getInputStream(events).bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).forEach { line ->
                val event = json.parseToJsonElement(line).jsonObject
                val id = event["operationId"]?.jsonPrimitive?.content ?: "?"
                val parent = event["parentOperationId"]?.jsonPrimitive?.content ?: "-"
                val kind = event["kind"]?.jsonPrimitive?.content ?: "?"
                if (kind.endsWith("START")) starts += id
                if (kind.endsWith("END") || kind.endsWith("ERROR") || kind.endsWith("CANCEL") || kind.endsWith("CANCELLED")) ends += id
                val attributes = event["attributes"]?.jsonObject
                val callId = attributes?.get("call_id")?.jsonPrimitive?.content
                if (callId != null) {
                    val key = id to callId
                    if (kind == "TOOL_CALL" && attributes?.containsKey("arguments_valid") == true) {
                        pendingCalls[key] = (pendingCalls[key] ?: 0) + 1
                    } else if (kind == "TOOL_RESULT") {
                        val pending = pendingCalls[key] ?: 0
                        if (pending == 0) orphanResults.add(key)
                        else if (pending == 1) pendingCalls.remove(key)
                        else pendingCalls[key] = pending - 1
                    }
                }
                if (filter == null || filter == id || filter == parent) {
                    println("${event["timestampMillis"] ?: "?"} $id parent=$parent $kind")
                    count++
                }
            }
        }
        println("Events shown: $count; operations without a terminal event: ${starts - ends}")
        println("Unmatched tool calls: $pendingCalls; results without calls: $orphanResults")
        println("Missing pairs can also result from incomplete or filtered capture; repeated provider IDs are counted in order.")
    }
}
