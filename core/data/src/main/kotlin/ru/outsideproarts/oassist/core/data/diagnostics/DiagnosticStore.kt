package ru.outsideproarts.oassist.core.data.diagnostics

import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.FileProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.outsideproarts.oassist.core.data.secret.KeystoreAead
import ru.outsideproarts.oassist.core.data.atomicWrite
import ru.outsideproarts.oassist.core.model.Outcome
import ru.outsideproarts.oassist.core.model.SecretRedactor
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Base64
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

interface DiagnosticCipher {
    fun encrypt(data: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

private class KeystoreDiagnosticCipher : DiagnosticCipher {
    private val aead by lazy { KeystoreAead("oassist_diagnostic_master") }
    override fun encrypt(data: ByteArray): ByteArray = when (val result = aead.encrypt(data)) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> throw IllegalStateException("diagnostic encryption failed")
    }
    override fun decrypt(data: ByteArray): ByteArray = when (val result = aead.decrypt(data)) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> throw IllegalStateException("diagnostic decryption failed")
    }
}

/** Sanitizes before an event is handed to the asynchronous writer. */
internal object DiagnosticSanitizer {
    private val secretKey = Regex("(?i)^(authorization|proxy-authorization|cookie|set-cookie|api[_-]?key|x-api-key|x-goog-api-key|token|access[_-]?token|refresh[_-]?token|password|passwd|pwd|secret|client[_-]?secret|credential|extraheaders)$")
    private val json = Json { ignoreUnknownKeys = true }
    private val urlPattern = Regex("https?://[^\\s\\\"<>]+")
    private val jsonEscape = Regex("""\\(?:u[0-9a-fA-F]{4}|["\\/bfnrt])""")

    fun clean(value: String, secrets: Collection<String>): String {
        val parsed = if (value.trimStart().startsWith("{") || value.trimStart().startsWith("[")) {
            runCatching { json.parseToJsonElement(value) }.getOrNull()
        } else null
        if (parsed != null) return json.encodeToString(JsonElement.serializer(), cleanJson(parsed, secrets))

        // A truncated or malformed JSON body can still contain escaped credentials.
        val decoded = jsonEscape.replace(value) { match ->
            runCatching { json.decodeFromString<String>("\"${match.value}\"") }.getOrDefault(match.value)
        }
        val fallback = if (secrets.any { it.isNotEmpty() && decoded.contains(it) }) decoded else value
        val replaced = secrets.filter { it.isNotEmpty() }.fold(fallback) { text, secret ->
            val escaped = Json.encodeToString(secret).removeSurrounding("\"")
            text.replace(secret, "[REDACTED]").replace(escaped, "[REDACTED]")
        }
        return cleanText(replaced).replace(Regex("data:[^\\s\\\"]+;base64,[A-Za-z0-9+/=]+"), "[MEDIA_OMITTED_UNPARSEABLE]")
    }

    private fun cleanText(value: String): String = SecretRedactor.redact(urlPattern.replace(value) { match ->
        runCatching {
            val uri = URI(match.value)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        }.getOrDefault("[URL_REDACTED]")
    })

    private fun cleanJson(value: JsonElement, secrets: Collection<String>, declarations: Boolean = false): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { (key, item) ->
            redactKnown(key, secrets) to if (!declarations && secretKey.matches(key)) JsonPrimitive("[REDACTED]") else cleanJson(item, secrets, key == "properties")
        })
        is JsonArray -> JsonArray(value.map { cleanJson(it, secrets) })
        is JsonPrimitive -> {
            val redacted = redactKnown(value.content, secrets)
            if (value.isString) JsonPrimitive(cleanText(redacted))
            else if (redacted != value.content) JsonPrimitive(redacted) else value
        }
    }

    private fun redactKnown(value: String, secrets: Collection<String>): String =
        secrets.filter { it.isNotEmpty() }.fold(value) { text, secret -> text.replace(secret, "[REDACTED]") }

    fun attributes(attributes: Map<String, String>, secrets: Collection<String>): Map<String, String> =
        attributes.mapValues { (key, value) ->
            if (secretKey.matches(key)) "[REDACTED]" else clean(value, secrets)
        }
}

private data class CapturedMedia(val id: String, val bytes: ByteArray)

private fun extractMedia(content: String): Pair<String, List<CapturedMedia>> {
    val parsed = runCatching { Json.parseToJsonElement(content) }.getOrNull() ?: return content to emptyList()
    val media = mutableListOf<CapturedMedia>()
    fun visit(value: JsonElement, mediaContext: Boolean = false): JsonElement = when (value) {
        is JsonObject -> {
            val type = (value["type"] as? JsonPrimitive)?.content
            val isMedia = mediaContext || type in setOf("base64", "image", "audio")
            val mime = listOf("mimeType", "mime_type", "media_type").firstNotNullOfOrNull { key ->
                (value[key] as? JsonPrimitive)?.content
            } ?: if ((value["format"] as? JsonPrimitive)?.content == "wav") "audio/wav" else null
            JsonObject(value.mapValues { (key, child) ->
                val childContext = isMedia || key in setOf("inlineData", "inline_data", "input_audio", "source")
                if ((key == "data" && childContext || key in setOf("imageBase64", "wavBase64")) && child is JsonPrimitive && child.isString) {
                    visitEncoded(child.content, media, mime ?: if (key == "wavBase64") "audio/wav" else null)
                } else visit(child, childContext)
            })
        }
        is JsonArray -> JsonArray(value.map { visit(it, mediaContext) })
        is JsonPrimitive -> if (value.isString && value.content.startsWith("data:") && value.content.contains(";base64,")) {
            visitEncoded(value.content.substringAfter(";base64,"), media, value.content.substringAfter("data:").substringBefore(";"))
        } else value
    }
    return Json.encodeToString(JsonElement.serializer(), visit(parsed)) to media
}

private fun visitEncoded(encoded: String, media: MutableList<CapturedMedia>, mimeType: String?): JsonElement {
    if (encoded.length > 32L * 1024 * 1024 * 4 / 3 + 8) return JsonPrimitive("[MEDIA_OMITTED_TOO_LARGE]")
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        ?: return JsonPrimitive("[MEDIA_INVALID_BASE64]")
    val id = UUID.randomUUID().toString()
    media.add(CapturedMedia(id, bytes))
    val safeMime = mimeType?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) } ?: "application/octet-stream"
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return JsonPrimitive("diagnostic-media:$id;mime=$safeMime;sha256=$digest")
}

class DiagnosticStore private constructor(
    private val rootProvider: () -> File,
    private val cipher: DiagnosticCipher,
    private val loadEnabled: () -> Boolean,
    private val saveEnabled: (Boolean) -> Unit,
    private val clock: () -> Long,
    private val invalidateExports: () -> Unit,
    private val isAvailable: () -> Boolean,
    private val maxBytes: Long,
    private val reserveBytes: Long,
    private val maxMediaBytes: Long,
    private val deleteFile: (File) -> Boolean,
) : DiagnosticRecorder {
    constructor(context: Context) : this(
        { File(context.noBackupFilesDir, "diagnostics") }, KeystoreDiagnosticCipher(),
        { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getBoolean("content_enabled", true) },
        { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().putBoolean("content_enabled", it).apply() },
        System::currentTimeMillis,
        {
            val dir = File(context.cacheDir, "diagnostics_exports")
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                kotlin.runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                file.delete()
            }
        },
        { context.getSystemService(UserManager::class.java)?.isUserUnlocked == true },
        MAX_BYTES,
        16L * 1024 * 1024,
        128L * 1024 * 1024,
        { it.delete() },
    )

    internal constructor(root: File, cipher: DiagnosticCipher, clock: () -> Long = System::currentTimeMillis, maxBytes: Long = MAX_BYTES, reserveBytes: Long = 16L * 1024 * 1024, maxMediaBytes: Long = 128L * 1024 * 1024) :
        this({ root }, cipher, { true }, {}, clock, {}, { true }, maxBytes, reserveBytes, maxMediaBytes, { it.delete() })

    internal constructor(root: File, cipher: DiagnosticCipher, clock: () -> Long = System::currentTimeMillis, maxBytes: Long = MAX_BYTES, reserveBytes: Long = 16L * 1024 * 1024, maxMediaBytes: Long = 128L * 1024 * 1024, deleteFile: (File) -> Boolean) :
        this({ root }, cipher, { true }, {}, clock, {}, { true }, maxBytes, reserveBytes, maxMediaBytes, deleteFile)

    private val lock = Any()
    // Never hold this lock during archive I/O or while acquiring lock.
    private val admissionLock = Any()
    // Доступность хранилища (binder-проба UserManager) кэшируется после первого
    // успеха: probe зовётся на горячем пути record, в том числе с главного потока.
    private val available = AvailabilityLatch(isAvailable)
    // Кэш метаданных архива. Расшифровка каждого файла — binder-вызов в Android
    // Keystore; полный проход по архиву на каждом обращении надолго блокировал
    // вызывающего (старт хода ассистента). Кэш строится фоновым сканом при
    // создании и дополняется по мере записи событий; чтения фильтруются по
    // существованию файла, поэтому удаления не требуют правки кэша.
    private var eventsIndexed = false
    private val eventIndexCache = LinkedHashMap<String, EventIndex>()
    private val bindingCache = LinkedHashMap<String, Pair<File, DiagnosticEvent>>()
    private val unreadableFiles = mutableMapOf<String, Int>()
    private val loadedMarkers = mutableSetOf<String>()
    private val dropped = AtomicLong()
    private val pendingBytes = AtomicLong()
    private val generation = AtomicLong()
    private val root: File get() = rootProvider()
    private val sequence by lazy { AtomicLong(runCatching { File(root, "sequence").readText().toLong() }.getOrDefault(0)) }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256)) { task ->
        Thread(task, "oassist-diagnostic-writer").apply { isDaemon = true }
    }
    init {
        writer.prestartCoreThread()
        // Прогрев кэша метаданных в фоне: пока скан не завершён, bindConversation
        // пропускает обслуживание архива и не ждёт расшифровку сотен файлов.
        // Маркеры удаления здесь не трогаем: их загрузка — точка синхронизации
        // для записей, допущенных до применения tombstone (см. record/isRemoved).
        try {
            writer.execute { runCatching { buildIndex() } }
        } catch (_: RejectedExecutionException) {
        }
    }
    @Volatile private var enabled = if (available()) loadEnabled() else false
    @Volatile private var settingsLoaded = available()
    @Volatile private var failure: String? = null
    private val removedOperations = ConcurrentHashMap.newKeySet<String>()
    private val durablyRemovedOperations = mutableSetOf<String>()
    private val knownOperations = ConcurrentHashMap.newKeySet<String>()
    private val operationRoots = mutableMapOf<String, String>()
    private val liveRoots = mutableMapOf<String, String>()
    private val liveParents = mutableMapOf<String, String>()
    private val activeOperations = mutableSetOf<String>()
    private val pendingRoots = mutableMapOf<String, Int>()
    private val runBytes = mutableMapOf<String, Long>()
    private var runIndexLoaded = false
    private var startupMaintenanceDone = false

    private fun ensureStartupMaintenance() {
        if (startupMaintenanceDone || !available()) return
        loadDeletionMarkers()
        var cleaned = true
        val existing = files()
        val eventNames = existing.filter { it.extension == "event" }.map { it.nameWithoutExtension }.toSet()
        existing.filter { file ->
            file.name.endsWith(".partial") ||
                (file.extension == "media" && file.nameWithoutExtension.substringBeforeLast('.', "") !in eventNames)
        }.forEach {
            if (!it.delete()) {
                cleaned = false
                failure = "delete_failed"
            }
        }
        if (!cleanupDeletedEvents()) cleaned = false
        if (!cleanupUnownedEvents()) cleaned = false
        startupMaintenanceDone = cleaned
    }

    data class OperationSummary(val operationId: String, val timestampMillis: Long, val mode: String?, val chatId: String?)
    private data class EventIndex(val file: File, val operationId: String, val parent: String?, val root: String?, val kind: DiagnosticKind, val chatId: String?, val hasContent: Boolean, val contentBytes: Long)
    private data class ExportSnapshot(val health: DiagnosticHealth, val events: List<EventIndex>, val files: List<File>, val epoch: Long, val invalid: Int)
    @Serializable private data class DeletionMarker(val operationIds: Set<String>)
    private val pinned = mutableMapOf<File, Int>()

    fun generationToken(): Long = generation.get()

    // A descendant can enter the queue before its ancestors finish sanitization or tombstones load.
    private fun isRemoved(operationId: String?): Boolean = synchronized(admissionLock) {
        var current = operationId
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (current in removedOperations) return@synchronized true
            current = liveParents[current]
        }
        false
    }

    fun close() {
        writer.shutdown()
        writer.awaitTermination(5, TimeUnit.SECONDS)
    }

    fun listOperations(sinceMillis: Long): List<OperationSummary> {
        flush()
        return synchronized(lock) {
            ensureStartupMaintenance()
            val summaries = mutableMapOf<String, OperationSummary>()
            val started = mutableSetOf<String>()
            files().filter { it.extension == "event" }.forEach { file ->
                val event = runCatching { decode(file) }.getOrNull() ?: return@forEach
                if (event.timestampMillis < sinceMillis || event.parentOperationId != null) return@forEach
                if (event.kind == DiagnosticKind.OPERATION_START) started.add(event.operationId)
                val prior = summaries[event.operationId]
                summaries[event.operationId] = OperationSummary(event.operationId, minOf(prior?.timestampMillis ?: event.timestampMillis, event.timestampMillis), prior?.mode ?: event.attributes["mode"], prior?.chatId ?: event.attributes["chatId"])
            }
            summaries.values.filter { it.operationId in started }.sortedByDescending { it.timestampMillis }
        }
    }

    override fun record(event: DiagnosticEvent, secrets: Collection<String>) {
        val (liveRoot, currentGeneration) = synchronized(admissionLock) {
            val rootId = liveRoots[event.operationId] ?: event.parentOperationId?.let { liveRoots[it] ?: it } ?: event.operationId
            if (isRemoved(event.operationId) || isRemoved(event.parentOperationId) || isRemoved(rootId)) {
                removedOperations.add(event.operationId)
                dropped.incrementAndGet()
                return
            }
            knownOperations.add(event.operationId)
            liveRoots[event.operationId] = rootId
            event.parentOperationId?.let { liveParents[event.operationId] = it }
            if (event.kind == DiagnosticKind.OPERATION_START) activeOperations.add(event.operationId)
            pendingRoots[rootId] = (pendingRoots[rootId] ?: 0) + 1
            rootId to generation.get()
        }
        if (!available()) {
            dropped.incrementAndGet()
            failure = "credential_storage_locked"
            finishPending(event, liveRoot)
            return
        }
        val rawContent = if (enabled || !settingsLoaded) event.content?.let { DiagnosticSanitizer.clean(it, secrets) } else null
        val (cleanContent, media) = rawContent?.let(::extractMedia) ?: (null to emptyList())
        val limited = cleanContent?.take(MAX_PAYLOAD_CHARS)
        val safe = event.copy(
            operationId = DiagnosticSanitizer.clean(event.operationId, secrets),
            parentOperationId = event.parentOperationId?.let { DiagnosticSanitizer.clean(it, secrets) },
            rootOperationId = DiagnosticSanitizer.clean(liveRoot, secrets),
            attributes = DiagnosticSanitizer.attributes(event.attributes, secrets),
            content = limited,
            sequence = 0,
            truncated = event.truncated || (cleanContent != null && cleanContent.length > MAX_PAYLOAD_CHARS),
        )
        // Reserve room for the sequence assigned by the writer without reading its file here.
        val queuedBytes = json.encodeToString(safe).toByteArray(StandardCharsets.UTF_8).size.toLong() +
            20 + media.sumOf { it.bytes.size.toLong() }
        if (pendingBytes.addAndGet(queuedBytes) > 64L * 1024 * 1024) {
            pendingBytes.addAndGet(-queuedBytes)
            dropped.incrementAndGet()
            failure = "queue_bytes_full"
            finishPending(event, liveRoot)
            return
        }
        try {
            writer.execute {
                try { synchronized(lock) {
                    if (currentGeneration != generation.get() || isRemoved(safe.operationId) || isRemoved(safe.parentOperationId) || isRemoved(liveRoot)) {
                        if (isRemoved(liveRoot) || isRemoved(safe.parentOperationId)) removedOperations.add(safe.operationId)
                        dropped.incrementAndGet()
                        return@synchronized
                    }
                    val ownedFiles = mutableListOf<File>()
                    var published = false
                    try {
                        if (!settingsLoaded) {
                            enabled = loadEnabled()
                            settingsLoaded = true
                            if (failure == "credential_storage_locked") failure = null
                        }
                        loadDeletionMarkers()
                        ensureStartupMaintenance()
                        if (isRemoved(safe.operationId) || isRemoved(safe.parentOperationId) || isRemoved(liveRoot)) {
                            removedOperations.add(safe.operationId)
                            dropped.incrementAndGet()
                            return@synchronized
                        }
                        if (!root.exists() && !root.mkdirs()) error("storage_unavailable")
                        loadRunIndex()
                        val runId = safe.rootOperationId ?: operationRoots[safe.operationId] ?: safe.parentOperationId?.let { operationRoots[it] ?: it } ?: safe.operationId
                        operationRoots[safe.operationId] = runId
                        val used = runBytes[runId] ?: 0
                        val sequenced = safe.copy(content = if (enabled) safe.content else null, sequence = sequence.incrementAndGet())
                        val textBytes = sequenced.content?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong() ?: 0
                        val allowed = used + textBytes <= 32L * 1024 * 1024
                        val storedEvent = if (allowed) sequenced else sequenced.copy(content = null, truncated = true)
                        if (!allowed) dropped.incrementAndGet() else runBytes[runId] = used + textBytes
                        val bytes = cipher.encrypt(json.encodeToString(storedEvent).toByteArray(StandardCharsets.UTF_8))
                        rotate(bytes.size.toLong(), storedEvent.content != null, runId)
                        val sequenceFile = File(root, "sequence")
                        val sequencePending = File(root, "sequence.pending")
                        sequencePending.writeText(sequence.get().toString())
                        if (sequenceFile.exists() && !sequenceFile.delete()) error("sequence_write_failed")
                        if (!sequencePending.renameTo(sequenceFile)) error("sequence_write_failed")
                        val name = "${storedEvent.sequence.toString().padStart(20, '0')}-${UUID.randomUUID()}"
                        val partial = File(root, "$name.partial")
                        ownedFiles.add(partial)
                        partial.writeBytes(bytes)
                        (if (allowed && enabled) media else emptyList()).forEach { blob ->
                            val encrypted = cipher.encrypt(blob.bytes)
                            rotateMedia(encrypted.size.toLong(), runId, ownedFiles.toSet())
                            rotate(encrypted.size.toLong(), true, runId, ownedFiles.toSet())
                            val pending = File(root, "$name.${blob.id}.partial")
                            val mediaFile = File(root, "$name.${blob.id}.media")
                            ownedFiles.add(pending)
                            ownedFiles.add(mediaFile)
                            pending.writeBytes(encrypted)
                            if (!pending.renameTo(mediaFile)) error("media_publish_failed")
                        }
                        val eventFile = File(root, "$name.event")
                        if (!partial.renameTo(eventFile)) error("publish_failed")
                        eventIndexCache[eventFile.name] = EventIndex(
                            eventFile,
                            storedEvent.operationId,
                            storedEvent.parentOperationId,
                            storedEvent.rootOperationId,
                            storedEvent.kind,
                            storedEvent.attributes["chatId"],
                            storedEvent.content != null,
                            if (storedEvent.content != null) textBytes else 0L,
                        )
                        published = true
                    } catch (e: Exception) {
                        dropped.incrementAndGet()
                        failure = when (e.message) {
                            "sequence_write_failed", "publish_failed", "media_publish_failed", "quota_exceeded", "media_quota_exceeded", "storage_unavailable" -> e.message
                            else -> "write_failed_${e.javaClass.simpleName}"
                        }
                    } finally {
                        if (!published) ownedFiles.forEach { file ->
                            if (file.exists() && !file.delete()) failure = "delete_failed"
                        }
                    }
                } } finally {
                    pendingBytes.addAndGet(-queuedBytes)
                    finishPending(event, liveRoot)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingBytes.addAndGet(-queuedBytes)
            dropped.incrementAndGet()
            failure = "queue_full"
            finishPending(event, liveRoot)
        }
    }

    private fun finishPending(event: DiagnosticEvent, rootId: String) = synchronized(admissionLock) {
        val remaining = (pendingRoots[rootId] ?: 1) - 1
        if (remaining == 0) pendingRoots.remove(rootId) else pendingRoots[rootId] = remaining
        if (event.kind == DiagnosticKind.OPERATION_END || event.kind == DiagnosticKind.OPERATION_ERROR ||
            event.kind == DiagnosticKind.OPERATION_CANCELLED
        ) activeOperations.remove(event.operationId)
    }

    override fun health(): DiagnosticHealth = synchronized(lock) {
        try {
            ensureStartupMaintenance()
        } catch (_: Exception) {
            return@synchronized DiagnosticHealth(false, enabled, dropped.get(), files().sumOf { it.length() }, failure ?: "storage_unavailable")
        }
        if (!available()) DiagnosticHealth(false, false, dropped.get(), 0, "credential_storage_locked")
        else DiagnosticHealth(failure == null, enabled, dropped.get(), files().sumOf { it.length() }, failure)
    }

    fun setContentEnabled(enabled: Boolean) {
        synchronized(lock) {
            synchronized(admissionLock) {
                generation.incrementAndGet()
                if (!enabled) removedOperations.addAll(knownOperations)
                this.enabled = enabled
            }
            saveEnabled(enabled)
        }
    }

    fun clear() = synchronized(lock) {
        ensureStartupMaintenance()
        synchronized(admissionLock) {
            generation.incrementAndGet()
            removedOperations.addAll(knownOperations)
            liveRoots.clear()
            liveParents.clear()
            activeOperations.clear()
        }
        operationRoots.clear()
        runBytes.clear()
        runIndexLoaded = true
        files().filter { it.name != "sequence" && it.extension != "deleted" }.forEach { if (!it.delete()) failure = "delete_failed" }
        eventIndexCache.clear()
        bindingCache.clear()
        unreadableFiles.clear()
        // Кэш считается непостроенным: после сброса индекс восстановится с диска,
        // и файлы, которые не удалось удалить, останутся видимы.
        eventsIndexed = false
        invalidateExports()
    }

    /**
     * Persist ownership outside the lossy event queue before recording request content.
     *
     * Путь вызывающего не сканирует архив: расшифровка каждого файла — отдельный
     * binder-вызов в Android Keystore, а полный проход по архиву на каждый ход
     * блокировал старт стрима ассистента на десятки секунд. Обслуживание архива
     * (ротация, чистка биндов) выполняется синхронно только когда кэш метаданных
     * уже построен, иначе откладывается в writer.
     */
    fun bindConversation(chatId: String, operationId: String) = synchronized(lock) {
        try {
            loadDeletionMarkers()
        } catch (_: Exception) {
            removedOperations.add(operationId)
            dropped.incrementAndGet()
            return@synchronized
        }
        if (operationId in removedOperations) return@synchronized
        var pending: File? = null
        try {
            check(available()) { "storage_unavailable" }
            if (!root.exists() && !root.mkdirs()) error("storage_unavailable")
            if (eventsIndexed) {
                ensureStartupMaintenance()
                rotate(0, false, operationId)
                pruneExpiredBindings()
            } else {
                deferMaintenance(operationId)
            }
            if (isBound(chatId, operationId)) return@synchronized
            // Имя детерминировано парой (chatId, operationId): повторный bind
            // идемпотентен и не требует расшифровки существующих биндов.
            val target = File(root, "bind-${bindingDigest(chatId, operationId)}.binding")
            if (target.exists()) return@synchronized
            val event = DiagnosticEvent(DiagnosticKind.STORAGE, operationId, attributes = mapOf("chatId" to chatId), timestampMillis = clock())
            val bytes = cipher.encrypt(json.encodeToString(event).toByteArray(StandardCharsets.UTF_8))
            val allFiles = files()
            if (allFiles.filter { it.extension == "binding" }.sumOf { it.length() } + bytes.size > reserveBytes ||
                allFiles.sumOf { it.length() } + pendingBytes.get() + bytes.size > maxBytes
            ) error("binding_quota_exceeded")
            pending = File(root, "${target.name}.partial")
            pending.writeBytes(bytes)
            if (!pending.renameTo(target)) error("publish_failed")
            bindingCache[target.name] = target to event
        } catch (e: Exception) {
            removedOperations.add(operationId)
            val events = eventIndex()
            val affected = mutableSetOf(operationId)
            val (roots, parents) = synchronized(admissionLock) { liveRoots.toMap() to liveParents.toMap() }
            do {
                val count = affected.size
                events.filter { it.parent in affected || it.root in affected }.forEach { affected.add(it.operationId) }
                events.filter { it.root in affected }.mapNotNullTo(affected) { it.parent }
                roots.filterValues { it in affected }.keys.forEach { affected.add(it) }
                parents.filterValues { it in affected }.keys.forEach { affected.add(it) }
            } while (affected.size != count)
            removedOperations.addAll(affected)
            events.filter { it.operationId in affected }.forEach { entry ->
                val prefix = entry.file.nameWithoutExtension + "."
                files().filter { it == entry.file || it.name.startsWith(prefix) }.forEach { it.delete() }
            }
            dropped.incrementAndGet()
            failure = "binding_failed_${e.javaClass.simpleName}"
        } finally {
            pending?.let { if (it.exists() && !it.delete()) failure = "delete_failed" }
        }
    }

    /** Обслуживание архива вне пути вызывающего: холодный bind не ждёт скан метаданных. */
    private fun deferMaintenance(operationId: String) {
        try {
            writer.execute {
                // Скан — вне lock (как прогрев): обслуживание не должно
                // блокировать bind'ы на всё время расшифровки архива.
                runCatching { buildIndex() }
                runCatching {
                    synchronized(lock) {
                        ensureStartupMaintenance()
                        rotate(0, false, operationId)
                        pruneExpiredBindings()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun isBound(chatId: String, operationId: String): Boolean =
        bindingEntries().any { (_, binding) ->
            binding.operationId == operationId && binding.attributes["chatId"] == chatId
        }

    private fun bindingDigest(chatId: String, operationId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((chatId + 0.toChar() + operationId).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun removeConversation(chatId: String, operationIds: Set<String> = emptySet()) =
        removeOwnedOperations(chatId, operationIds)

    fun discardOperation(operationId: String) = removeOwnedOperations(null, setOf(operationId))

    private fun removeOwnedOperations(chatId: String?, operationIds: Set<String>) {
        flush()
        synchronized(lock) {
        check(available()) { "storage_unavailable" }
        check(root.isDirectory || (!root.exists() && root.mkdirs())) { "storage_unavailable" }
        loadDeletionMarkers()
        val events = eventIndex()
        // Файлы, которых нет в кэше, читаются с диска: нечитаемый бинд должен
        // ронять удаление, как и раньше (fail-closed), а не пропускаться молча.
        val bindings = files().filter { it.extension == "binding" }.map { file ->
            bindingCache[file.name] ?: (file to decode(file)).also { bindingCache[file.name] = it }
        }
        val matchingBindings = bindings.filter {
            (chatId != null && it.second.attributes["chatId"] == chatId) || it.second.operationId in operationIds
        }
        val affected = events.filter { chatId != null && it.chatId == chatId }.map { it.operationId }.toMutableSet().apply {
            addAll(operationIds)
            addAll(matchingBindings.map { it.second.operationId })
        }
        val (roots, parents) = synchronized(admissionLock) { liveRoots.toMap() to liveParents.toMap() }
        do {
            val count = affected.size
            events.filter { it.parent in affected || it.root in affected }.forEach { affected.add(it.operationId) }
            events.filter { it.root in affected }.mapNotNullTo(affected) { it.parent }
            roots.filterValues { it in affected }.keys.forEach { affected.add(it) }
            parents.filterValues { it in affected }.keys.forEach { affected.add(it) }
        } while (affected.size != count)
        // Reject descendants before publishing the marker without blocking admission on I/O.
        val newlyRemoved = synchronized(admissionLock) {
            generation.incrementAndGet()
            (affected - removedOperations).also { removedOperations.addAll(affected) }
        }
        try {
            if (affected.isNotEmpty()) {
                publishDeletionMarker(affected)
            }
        } catch (error: Exception) {
            synchronized(admissionLock) { removedOperations.removeAll(newlyRemoved) }
            throw error
        }
        matchingBindings.forEach { if (!deleteFile(it.first)) failure = "delete_failed" }
        events.filter { it.operationId in affected }.forEach { entry ->
            val file = entry.file
            val prefix = file.nameWithoutExtension + "."
            var mediaDeleted = true
            files().filter { it != file && it.name.startsWith(prefix) }.forEach {
                if (!deleteFile(it)) { failure = "delete_failed"; mediaDeleted = false }
            }
            if (mediaDeleted && !deleteFile(file)) failure = "delete_failed"
        }
        cleanupUnownedEvents()
        invalidateExports()
        }
    }

    fun export(output: OutputStream, sinceMillis: Long = clock() - RETENTION_MILLIS, includeMedia: Boolean = false, operationIds: Set<String>? = null) {
        flush()
        val snapshot = synchronized(lock) {
            ensureStartupMaintenance()
            loadDeletionMarkers()
            val all = files()
            val indexed = eventIndex()
            val owned = ownedOperationIds(indexed)
            val events = indexed.filter { it.operationId in owned && !isRemoved(it.operationId) && !isRemoved(it.parent) && !isRemoved(it.root) }
            val held = all.filter { it.extension == "event" || it.extension == "media" }
            held.forEach { pinned[it] = (pinned[it] ?: 0) + 1 }
            ExportSnapshot(health(), events, held, generation.get(), all.count { it.extension == "event" } - indexed.size)
        }
        try {
            val status = snapshot.health
            val eventFiles = snapshot.events
            val epoch = snapshot.epoch
            fun ensureCurrent() { check(generation.get() == epoch) { "export_invalidated" } }
            val selected = operationIds?.toMutableSet()
            if (selected != null) {
                do {
                    val count = selected.size
                    eventFiles.filter { it.parent in selected || it.root in selected }.forEach { selected.add(it.operationId) }
                } while (selected.size != count)
            }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("events.jsonl"))
                var invalid = 0
                var truncated = 0
                val started = mutableSetOf<String>()
                val terminal = mutableSetOf<String>()
                invalid += snapshot.invalid.coerceAtLeast(0)
                eventFiles.sortedBy { it.file.name }.forEach { entry ->
                    ensureCurrent()
                    val event = runCatching { decode(entry.file) }.getOrElse { invalid++; return@forEach }
                    if (event.timestampMillis >= sinceMillis) {
                        if (selected == null || event.operationId in selected) {
                            if (event.truncated) truncated++
                            if (event.kind == DiagnosticKind.OPERATION_START) started.add(event.operationId)
                            if (event.kind in setOf(DiagnosticKind.OPERATION_END, DiagnosticKind.OPERATION_ERROR, DiagnosticKind.OPERATION_CANCELLED)) terminal.add(event.operationId)
                        }
                        val projected = if (selected == null || event.operationId in selected) event else event.copy(
                            content = null,
                            attributes = event.attributes.filterKeys { it in setOf("mode", "status", "durationMillis", "errorType", "protocol") },
                        )
                        zip.write(json.encodeToString(projected).toByteArray(StandardCharsets.UTF_8))
                        zip.write('\n'.code)
                    }
                }
                zip.closeEntry()
                if (includeMedia) {
                    eventFiles.forEach { entry ->
                        ensureCurrent()
                        val eventFile = entry.file
                        val event = runCatching { decode(eventFile) }.getOrElse { invalid++; return@forEach }
                        if (event.timestampMillis >= sinceMillis && (selected == null || event.operationId in selected)) {
                            val prefix = eventFile.nameWithoutExtension + "."
                            snapshot.files.filter { it.extension == "media" && it.name.startsWith(prefix) }.forEach { blob ->
                                val data = runCatching { cipher.decrypt(blob.readBytes()) }.getOrElse { invalid++; null }
                                if (data != null) {
                                    val id = blob.name.removePrefix(prefix).removeSuffix(".media")
                                    zip.putNextEntry(ZipEntry("media/$id.bin"))
                                    zip.write(data)
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                val missingTerminal = (started - terminal).size
                val reasons = buildList {
                    add("coverage_unverified")
                    if (status.droppedEvents > 0) add("dropped_events")
                    if (invalid > 0) add("corrupt_events")
                    if (truncated > 0) add("truncated_events")
                    if (missingTerminal > 0) add("missing_terminal")
                }
                val encodedReasons = json.encodeToString(reasons)
                val manifest = "{\"version\":1,\"sanitized\":true,\"droppedEvents\":${status.droppedEvents},\"corruptEvents\":$invalid,\"truncatedEvents\":$truncated,\"missingTerminal\":$missingTerminal,\"incompleteReasons\":$encodedReasons,\"mediaRequested\":$includeMedia,\"mediaIncluded\":$includeMedia,\"complete\":false}"
                zip.write(manifest.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("summary.md"))
                zip.write("# OAssist diagnostics\n\nDropped events: ${status.droppedEvents}\nCorrupt events: $invalid\nTruncated events: $truncated\nMissing terminal events: $missingTerminal\nIncomplete: ${reasons.joinToString()}\n".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                ensureCurrent()
            }
        } finally {
            synchronized(lock) {
                snapshot.files.forEach { file ->
                    val remaining = (pinned[file] ?: 1) - 1
                    if (remaining == 0) pinned.remove(file) else pinned[file] = remaining
                }
            }
        }
    }

    private fun decode(file: File): DiagnosticEvent =
        json.decodeFromString<DiagnosticEvent>(cipher.decrypt(file.readBytes()).toString(StandardCharsets.UTF_8))

    /**
     * Вызывается с удержанием [lock]. Пока кэш не построен (фоновый скан ещё
     * идёт), строит его явно — этим пользуются только операции вне горячего
     * пути (экспорт, удаление, обслуживание).
     */
    private fun eventIndex(): List<EventIndex> {
        if (!eventsIndexed) buildIndex()
        retryUnreadable()
        return eventIndexCache.values.filter { it.file.exists() }.also { alive ->
            if (alive.size != eventIndexCache.size) {
                eventIndexCache.keys.retainAll(alive.mapTo(mutableSetOf()) { it.file.name })
            }
        }
    }

    /** Живые бинды из кэша; записи пропавших файлов забываются. */
    private fun bindingEntries(): List<Pair<File, DiagnosticEvent>> =
        bindingCache.values.filter { (file, _) -> file.exists() }.also { alive ->
            if (alive.size != bindingCache.size) {
                bindingCache.keys.retainAll(alive.mapTo(mutableSetOf()) { it.first.name })
            }
        }

    /**
     * Полный скан метаданных без удержания [lock]: расшифровка сотен файлов не
     * должна блокировать вызывающих. Публикация — короткая, под [lock]; записи,
     * добавленные writer'ом параллельно, не перезаписываются.
     *
     * Индекс помечается полным только после реального прохода: если архив
     * недоступен (direct boot до разблокировки, сбой каталога), следующее
     * обращение повторит скан. Нерасшифрованные файлы запоминаются и
     * повторяются при чтении — транзиентный сбой Keystore не должен делать
     * индекс неполным навсегда.
     */
    private fun buildIndex() {
        synchronized(lock) { if (eventsIndexed) return }
        if (!available()) return
        val listing = root.listFiles() ?: return
        val scannedEvents = mutableListOf<Pair<File, EventIndex>>()
        val unreadable = mutableSetOf<String>()
        listing.filter { it.isFile && it.extension == "event" }.forEach { file ->
            val decoded = runCatching { decode(file) }.getOrNull()
            if (decoded != null) scannedEvents += file to decoded.toIndex(file) else unreadable.add(file.name)
        }
        val scannedBindings = listing.filter { it.isFile && it.extension == "binding" }.mapNotNull { file ->
            runCatching { decode(file) }.getOrNull()?.let { file to it }
        }
        synchronized(lock) {
            if (!eventsIndexed) {
                scannedEvents.forEach { (file, entry) -> if (file.exists()) eventIndexCache[file.name] = entry }
                scannedBindings.forEach { (file, event) -> if (file.exists()) bindingCache[file.name] = file to event }
                unreadable.forEach { unreadableFiles.putIfAbsent(it, 0) }
                eventsIndexed = true
            }
        }
    }

    /**
     * Повтор только тех файлов, что не расшифровались при скане: транзиентный
     * сбой Keystore не должен делать индекс неполным навсегда. Пропавшие файлы
     * забываются, стабильно нечитаемые — прекращают попытки и остаются в счётчике
     * повреждённых событий экспорта.
     */
    private fun retryUnreadable() {
        if (unreadableFiles.isEmpty()) return
        unreadableFiles.keys.toList().forEach { name ->
            val attempts = unreadableFiles[name] ?: return@forEach
            val file = File(root, name)
            if (attempts >= MAX_DECODE_ATTEMPTS || !file.exists()) {
                unreadableFiles.remove(name)
                return@forEach
            }
            val decoded = runCatching { decode(file) }.getOrNull()
            if (decoded == null) {
                unreadableFiles[name] = attempts + 1
                return@forEach
            }
            val entry = decoded.toIndex(file)
            eventIndexCache[name] = entry
            unreadableFiles.remove(name)
            if (runIndexLoaded) {
                val rootId = decoded.rootOperationId ?: decoded.operationId
                operationRoots[decoded.operationId] = rootId
                runBytes[rootId] = (runBytes[rootId] ?: 0) + entry.contentBytes
            }
        }
    }

    private fun DiagnosticEvent.toIndex(file: File) = EventIndex(
        file,
        operationId,
        parentOperationId,
        rootOperationId,
        kind,
        attributes["chatId"],
        content != null,
        content?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong() ?: 0,
    )

    private fun loadDeletionMarkers() {
        files().filter { it.extension == "deleted" }.forEach { file ->
            // Маркеры неизменяемы и не удаляются: повторный проход читает только новое.
            if (file.name in loadedMarkers) return@forEach
            val marker = try {
                json.decodeFromString<DeletionMarker>(cipher.decrypt(file.readBytes()).toString(StandardCharsets.UTF_8))
            } catch (error: Exception) {
                failure = "deletion_marker_unreadable"
                throw IllegalStateException("deletion_marker_unreadable", error)
            }
            removedOperations.addAll(marker.operationIds)
            durablyRemovedOperations.addAll(marker.operationIds)
            loadedMarkers.add(file.name)
        }
    }

    private fun publishDeletionMarker(operationIds: Set<String>) {
        val marker = File(root, "${UUID.randomUUID()}.deleted")
        val bytes = cipher.encrypt(json.encodeToString(DeletionMarker(operationIds)).toByteArray(StandardCharsets.UTF_8))
        atomicWrite(marker) { it.write(bytes) }
        durablyRemovedOperations.addAll(operationIds)
        loadedMarkers.add(marker.name)
    }

    private fun cleanupDeletedEvents(): Boolean {
        var cleaned = true
        val entries = eventIndex()
        val affected = removedOperations.toMutableSet()
        do {
            val count = affected.size
            entries.filter { it.parent in affected || it.root in affected }.forEach { affected.add(it.operationId) }
        } while (affected.size != count)
        removedOperations.addAll(affected)
        entries.filter { it.operationId in affected }.forEach { entry ->
            val prefix = entry.file.nameWithoutExtension + "."
            var mediaDeleted = true
            files().filter { it != entry.file && it.name.startsWith(prefix) }.forEach {
                if (!deleteFile(it)) { failure = "delete_failed"; cleaned = false; mediaDeleted = false }
            }
            if (mediaDeleted && !deleteFile(entry.file)) { failure = "delete_failed"; cleaned = false }
        }
        files().filter { it.extension == "binding" }.forEach { file ->
            val event = runCatching { decode(file) }.getOrNull()
            if (event?.operationId in affected && !deleteFile(file)) { failure = "delete_failed"; cleaned = false }
        }
        return cleaned
    }

    private fun ownedOperationIds(entries: List<EventIndex>): Set<String> {
        val bindings = files().filter { it.extension == "binding" }.mapNotNull { file ->
            runCatching { decode(file).operationId }.getOrNull()
        }
        val roots = mutableMapOf<String, MutableSet<String>>()
        fun addRoot(operation: String, root: String) {
            roots.getOrPut(operation) { mutableSetOf() }.add(root)
        }
        bindings.forEach { addRoot(it, it) }
        entries.forEach { entry ->
            entry.root?.let { addRoot(it, it); addRoot(entry.operationId, it) }
            if (entry.kind == DiagnosticKind.OPERATION_START && entry.parent == null) addRoot(entry.operationId, entry.operationId)
        }
        do {
            var changed = false
            entries.forEach { entry ->
                val parentRoots = roots[entry.parent] ?: return@forEach
                if (roots.getOrPut(entry.operationId) { mutableSetOf() }.addAll(parentRoots)) changed = true
            }
        } while (changed)
        return roots.filterValues { it.size == 1 }.keys
    }

    private fun cleanupUnownedEvents(): Boolean {
        val entries = eventIndex()
        val owned = ownedOperationIds(entries)
        val unowned = entries.filter { it.operationId !in owned }.map { it.operationId }.toSet()
        if (unowned.isEmpty()) return true
        val unmarked = unowned - durablyRemovedOperations
        if (unmarked.isNotEmpty()) {
            try {
                generation.incrementAndGet()
                publishDeletionMarker(unmarked)
                removedOperations.addAll(unmarked)
            } catch (_: Exception) {
                failure = "deletion_marker_write_failed"
                return false
            }
        }
        var cleaned = true
        entries.filter { it.operationId in unowned }.forEach { entry ->
            val prefix = entry.file.nameWithoutExtension + "."
            files().filter { it == entry.file || it.name.startsWith(prefix) }.forEach { file ->
                if (!deleteFile(file)) { failure = "delete_failed"; cleaned = false }
            }
        }
        return cleaned
    }

    private fun pruneExpiredBindings() {
        val eventFiles = files().filter { it.extension == "event" }
        val entries = eventIndex()
        if (entries.size != eventFiles.size) return
        val bindingFiles = files().filter { it.extension == "binding" }
        val bindings = bindingEntries()
        if (bindings.size != bindingFiles.size) return
        val (roots, liveRetained) = synchronized(admissionLock) {
            liveRoots.toMap() to (activeOperations.map { liveRoots[it] ?: it }.toSet() + pendingRoots.keys)
        }
        val knownIds = entries.map { it.operationId }.toSet() + bindings.map { it.second.operationId } + roots.keys
        if (entries.any { it.parent != null && it.parent !in knownIds }) return
        val parents = entries.groupBy { it.operationId }.mapValues { (_, events) -> events.firstNotNullOfOrNull { it.parent } }
        fun rootOf(id: String): String {
            var current = id
            val seen = mutableSetOf<String>()
            while (seen.add(current)) current = parents[current] ?: break
            return roots[current] ?: current
        }
        val retained = entries.map { it.root ?: rootOf(it.operationId) }.toSet() + liveRetained
        bindings.forEach { (file, binding) ->
            if (binding.timestampMillis < clock() - RETENTION_MILLIS && binding.operationId !in retained) {
                if (!file.delete()) failure = "delete_failed"
            }
        }
    }

    private fun loadRunIndex() {
        if (runIndexLoaded) return
        // Пока индекс не построен (архив недоступен), счётчики прогонов не
        // фиксируем: иначе неполный проход исказит лимит контента на прогон.
        if (!eventsIndexed) return
        val entries = eventIndex()
        val parents = entries.groupBy { it.operationId }.mapValues { (_, events) -> events.firstNotNullOfOrNull { it.parent } }
        // Пересборка с нуля, а не аддитивное слияние: счётчик мог пополняться
        // построчной записью writer'а до построения индекса.
        operationRoots.clear()
        runBytes.clear()
        entries.forEach { entry ->
            var rootId = entry.root ?: entry.operationId
            val seen = mutableSetOf<String>()
            while (seen.add(rootId)) rootId = parents[rootId] ?: break
            operationRoots[entry.operationId] = rootId
            runBytes[rootId] = (runBytes[rootId] ?: 0) + entry.contentBytes
        }
        runIndexLoaded = true
    }

    private fun files(): List<File> = root.listFiles()?.filter { it.isFile } ?: emptyList()

    private fun flush() {
        check(!writer.isShutdown) { "diagnostics_closed" }
        val barrier = FutureTask { Unit }
        writer.queue.put(barrier)
        barrier.get()
    }

    private fun rotate(incoming: Long, content: Boolean, protectedRun: String, protectedFiles: Set<File> = emptySet()) {
        val all = files().filter { it.name != "sequence" }.sortedBy { it.lastModified() }
        var bytes = all.sumOf { it.length() }
        val limit = if (content) maxBytes - reserveBytes else maxBytes
        if (bytes + incoming > limit || all.any { it.lastModified() < clock() - RETENTION_MILLIS }) {
            val groups = evictionGroups(contentOnly = content, protectedRun = protectedRun)
            groups.forEach { group ->
                if (group.any { it in pinned || it in protectedFiles }) return@forEach
                if (group.maxOf { it.lastModified() } < clock() - RETENTION_MILLIS || bytes + incoming > limit) {
                    group.forEach { file ->
                        val length = file.length()
                        if (file.delete()) { bytes -= length; dropped.incrementAndGet() }
                    }
                }
            }
        }
        if (bytes + incoming > limit) error("quota_exceeded")
    }

    private fun rotateMedia(incoming: Long, protectedRun: String, protectedFiles: Set<File> = emptySet()) {
        val media = files().filter { it.extension == "media" }
        var bytes = media.sumOf { it.length() }
        if (bytes + incoming <= maxMediaBytes) return
        evictionGroups(contentOnly = true, protectedRun = protectedRun).filter { group -> group.any { it.extension == "media" } }.forEach { group ->
            if (group.any { it in pinned || it in protectedFiles }) return@forEach
            if (bytes + incoming > maxMediaBytes) {
                group.forEach { file ->
                    val size = file.length()
                    if (file.delete()) { if (file.extension == "media") bytes -= size; dropped.incrementAndGet() }
                }
            }
        }
        if (bytes + incoming > maxMediaBytes) error("media_quota_exceeded")
    }

    private fun evictionGroups(contentOnly: Boolean, protectedRun: String): List<List<File>> {
        val entries = eventIndex()
        val parents = entries.groupBy { it.operationId }.mapValues { (_, events) -> events.firstNotNullOfOrNull { it.parent } }
        fun root(id: String): String {
            var current = id
            val visited = mutableSetOf<String>()
            while (visited.add(current)) current = parents[current] ?: break
            return current
        }
        val allMedia = files().filter { it.extension == "media" }
        val groups = entries.filter { !contentOnly || it.hasContent }.groupBy { it.root ?: root(it.operationId) }
            .filterKeys { it != protectedRun }
            .values.map { group ->
                val eventFiles = group.map { it.file }
                eventFiles + allMedia.filter { blob -> eventFiles.any { blob.name.startsWith(it.nameWithoutExtension + ".") } }
            }
        val included = groups.flatten().toSet()
        val orphans = allMedia.filter { it !in included && entries.none { event -> it.name.startsWith(event.file.nameWithoutExtension + ".") } }.map { listOf(it) }
        return (groups + orphans).filter { it.isNotEmpty() }.sortedBy { group -> group.minOf { it.lastModified() } }
    }

    private companion object {
        const val MAX_PAYLOAD_CHARS = 4 * 1024 * 1024
        const val MAX_BYTES = 256L * 1024 * 1024
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAX_DECODE_ATTEMPTS = 3
    }
}
