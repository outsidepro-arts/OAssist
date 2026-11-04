package ru.outsideproarts.oassist.core.net.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import ru.outsideproarts.oassist.core.model.update.UpdateCandidate
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import ru.outsideproarts.oassist.core.model.update.UpdateManifest
import ru.outsideproarts.oassist.core.net.NoAutoRetry
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Failures are never interpreted as an empty update catalog. */
open class UpdateTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)
class UpdateRateLimitException(val retryAtMillis: Long) : UpdateTransportException("GitHub rate limit reached")
class UpdateHttpException(val status: Int) : UpdateTransportException("GitHub request failed: $status")
class UpdateDataException(message: String, cause: Throwable? = null) : UpdateTransportException(message, cause)

class GitHubUpdateTransport(client: HttpClient, private val now: () -> Long = System::currentTimeMillis) {
    fun close() { client.close() }
    private val client = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private data class Cached(val etag: String, val bytes: ByteArray)
    private val cache = mutableMapOf<String, Cached>()
    @Volatile private var blockedUntil = 0L

    private data class Descriptor(
        val code: Long,
        val name: String,
        val manifestUrl: String,
        val assets: Map<String, String>,
        val expected: UpdateChannel,
        val notes: String,
    )

    suspend fun findCandidates(channel: UpdateChannel): List<UpdateCandidate> = candidateFlow(channel, 0).toList()

    /** Loads manifests in descending version order, and stops as soon as the collector stops. */
    fun candidateFlow(channel: UpdateChannel, minVersionCode: Long): Flow<UpdateCandidate> = flow {
        val descriptors = mutableListOf<Descriptor>()
        for (release in pages("$API/releases")) {
            val tag = release.string("tag_name")
            val prerelease = release.boolean("prerelease")
            if (release.boolean("draft")) continue
            val development = tag == "updates-development"
            if (development && channel != UpdateChannel.DEVELOPMENT) continue
            if (!development && prerelease && channel == UpdateChannel.STABLE) continue
            val expected = if (development) UpdateChannel.DEVELOPMENT else if (prerelease) UpdateChannel.PRERELEASE else UpdateChannel.STABLE
            // Our tagged CI publishes one immutable build (APK, checksum and manifest) per tag.
            // The rolling development release can contain many builds and always needs pagination.
            val embedded = try { release["assets"]?.jsonArray?.map { it.jsonObject } }
                catch (e: RuntimeException) { throw UpdateDataException("Invalid release assets", e) }
            val listed = if (!development && embedded != null && embedded.size < 100) embedded else {
                val assetsUrl = release.string("assets_url")
                requireApiAssetsUrl(assetsUrl)
                pages(assetsUrl)
            }
            val assets = listed.associate { asset -> asset.string("name") to asset.string("browser_download_url") }
            val notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            for ((name, manifestUrl) in assets) {
                val match = MANIFEST_NAME.matchEntire(name) ?: continue
                val code = match.groupValues[1].toLongOrNull() ?: throw UpdateDataException("Invalid manifest version")
                if (code > minVersionCode) descriptors += Descriptor(code, name, manifestUrl, assets, expected, notes)
            }
        }
        for (descriptor in descriptors.sortedByDescending { it.code }) {
            requireReleaseUrl(descriptor.manifestUrl, descriptor.name)
            val manifest = try { json.decodeFromString<UpdateManifest>(readJson(descriptor.manifestUrl).decodeToString()) }
                catch (e: kotlinx.serialization.SerializationException) { throw UpdateDataException("Invalid update manifest", e) }
                catch (e: IllegalArgumentException) { throw UpdateDataException("Invalid update manifest", e) }
            validateManifest(manifest, descriptor.code, descriptor.expected)
            val apkUrl = descriptor.assets[manifest.apkName] ?: continue
            requireReleaseUrl(apkUrl, manifest.apkName)
            emit(UpdateCandidate(manifest, apkUrl, descriptor.notes))
        }
    }
    suspend fun isDescendant(base: String, candidate: String): Boolean {
        if (!SHA.matches(base) || !SHA.matches(candidate)) throw UpdateDataException("Invalid commit SHA")
        val data = parseObject(readJson("$API/compare/$base...$candidate"))
        return when (data.string("status")) {
            "ahead", "identical" -> true
            "behind", "diverged" -> false
            else -> throw UpdateDataException("Invalid comparison status")
        }
    }

    suspend fun download(
        candidate: UpdateCandidate,
        destination: File,
        commit: (File, File) -> Unit = { part, target ->
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val manifest = candidate.manifest
        validateManifest(manifest, manifest.versionCode, manifest.channel)
        requireReleaseUrl(candidate.apkUrl, manifest.apkName)
        val part = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            var url = candidate.apkUrl
            var redirects = 0
            while (true) {
                checkBlocked()
                var redirect: String? = null
                client.prepareGet(url) {
                    attributes.put(NoAutoRetry, Unit)
                    timeout { requestTimeoutMillis = 1_800_000; socketTimeoutMillis = 120_000 }
                }.execute { response ->
                    if (response.status.value in REDIRECTS) {
                        redirect = response.headers[HttpHeaders.Location] ?: throw UpdateDataException("Missing redirect location")
                    } else {
                        checkStatus(response)
                        withContext(Dispatchers.IO) {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val input = response.bodyAsChannel()
                            var total = 0L
                            part.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val count = input.readAvailable(buffer, 0, buffer.size)
                                    if (count == -1) break
                                    if (count == 0) continue
                                    total += count
                                    if (total > manifest.apkSize) throw UpdateDataException("APK exceeds declared size")
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    onProgress(total, manifest.apkSize)
                                }
                            }
                            if (total != manifest.apkSize || digest.digest().toHex() != manifest.apkSha256.lowercase())
                                throw UpdateDataException("APK size or checksum mismatch")
                        }
                    }
                }
                if (redirect == null) break
                if (++redirects > 5) throw UpdateDataException("Too many redirects")
                url = URI(url).resolve(redirect).toString()
                requireDownloadRedirect(url)
            }
            withContext(Dispatchers.IO) {
                commit(part, destination)
            }
        } finally { part.delete() }
    }

    private suspend fun pages(url: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val bytes = readJson("$url?per_page=100&page=$page")
            val items = try { json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject } }
                catch (e: RuntimeException) { throw UpdateDataException("Invalid GitHub response", e) }
            result.addAll(items)
            if (items.size < 100) return result
            if (page == Int.MAX_VALUE) throw UpdateDataException("Too many pages")
            page++
        }
    }

    private suspend fun readJson(initialUrl: String): ByteArray {
        val old = synchronized(cache) { cache[initialUrl] }
        var url = initialUrl
        var redirects = 0
        while (true) {
            checkBlocked()
            var next: String? = null
            var result: ByteArray? = null
            client.prepareGet(url) {
                attributes.put(NoAutoRetry, Unit)
                timeout { requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
                if (old != null) headers.append(HttpHeaders.IfNoneMatch, old.etag)
            }.execute { response ->
                when {
                    response.status.value == 304 -> result = old?.bytes ?: throw UpdateDataException("Unexpected 304")
                    response.status.value in REDIRECTS -> next = response.headers[HttpHeaders.Location] ?: throw UpdateDataException("Missing redirect location")
                    else -> {
                        checkStatus(response)
                        val bytes = readBounded(response)
                        response.headers[HttpHeaders.ETag]?.let { etag -> synchronized(cache) { cache[initialUrl] = Cached(etag, bytes) } }
                        result = bytes
                    }
                }
            }
            result?.let { return it }
            if (++redirects > 5) throw UpdateDataException("Too many redirects")
            url = URI(url).resolve(next!!).toString()
            requireDownloadRedirect(url)
        }
    }

    private suspend fun readBounded(response: HttpResponse): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            if (count == 0) continue
            if (out.size() + count > MAX_JSON_BYTES) throw UpdateDataException("JSON response too large")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun checkBlocked() { if (now() < blockedUntil) throw UpdateRateLimitException(blockedUntil) }
    private fun checkStatus(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val status = response.status.value
        if (status == 429 || (status == 403 && (response.headers["X-RateLimit-Remaining"] == "0" || response.headers[HttpHeaders.RetryAfter] != null))) {
            val retry = response.headers[HttpHeaders.RetryAfter]?.let { value ->
                value.toLongOrNull()?.let { now() + it.coerceAtLeast(0) * 1000 }
                    ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            }
            val reset = response.headers["X-RateLimit-Reset"]?.toLongOrNull()?.let { it * 1000 }
            blockedUntil = maxOf(now() + 60_000, retry ?: 0, reset ?: 0)
            throw UpdateRateLimitException(blockedUntil)
        }
        throw UpdateHttpException(status)
    }

    private fun validateManifest(m: UpdateManifest, code: Long, channel: UpdateChannel) {
        if (m.schemaVersion != 1 || m.applicationId != "ru.outsideproarts.oassist" || m.versionCode <= 0 || m.versionCode != code ||
            m.versionName.isBlank() || m.channel != channel || !SHA.matches(m.commitSha) || m.minSdk <= 0 ||
            m.abis.any { !Regex("[A-Za-z0-9][A-Za-z0-9_-]*").matches(it) } || !HASH.matches(m.certificateSha256) || !HASH.matches(m.apkSha256) ||
            m.apkSize <= 0 || !APK_NAME.matches(m.apkName) || m.sourceRef.isBlank())
            throw UpdateDataException("Invalid update manifest fields")
    }

    private fun parseObject(bytes: ByteArray): JsonObject = try { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
        catch (e: RuntimeException) { throw UpdateDataException("Invalid GitHub response", e) }
    private fun JsonObject.string(key: String): String = try { getValue(key).jsonPrimitive.let { require(it.isString); it.content } }
        catch (e: Exception) { throw UpdateDataException("Missing or invalid $key", e) }
    private fun JsonObject.boolean(key: String): Boolean = try { getValue(key).jsonPrimitive.boolean }
        catch (e: Exception) { throw UpdateDataException("Missing or invalid $key", e) }

    private fun requireApiAssetsUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host != "api.github.com" || uri.port != -1 || uri.userInfo != null ||
            !Regex("/repos/outsidepro-arts/OAssist/releases/[0-9]+/assets").matches(uri.path ?: "") || uri.query != null || uri.fragment != null)
            throw UpdateDataException("Invalid GitHub assets URL")
    }
    private fun requireReleaseUrl(url: String, assetName: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host != "github.com" || uri.port != -1 || uri.userInfo != null ||
            !(uri.rawPath ?: "").startsWith("/outsidepro-arts/OAssist/releases/download/") || uri.query != null || uri.fragment != null ||
            uri.path.split("/").any { it == "." || it == ".." } || uri.path.substringAfterLast('/') != assetName || uri.rawPath.contains("%2f", true) || uri.rawPath.contains("%5c", true))
            throw UpdateDataException("Invalid release asset URL")
    }
    private fun requireDownloadRedirect(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.port != -1 || uri.userInfo != null || uri.fragment != null ||
            uri.host !in setOf("release-assets.githubusercontent.com", "github-releases.githubusercontent.com", "objects.githubusercontent.com"))
            throw UpdateDataException("Invalid release redirect")
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    companion object {
        private const val API = "https://api.github.com/repos/outsidepro-arts/OAssist"
        private const val MAX_JSON_BYTES = 8 * 1024 * 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val MANIFEST_NAME = Regex("update-([1-9][0-9]*)\\.json")
        private val APK_NAME = Regex("[A-Za-z0-9._-]+\\.apk")
        private val SHA = Regex("[0-9a-fA-F]{40}")
        private val HASH = Regex("[0-9a-fA-F]{64}")
    }
}
