package ru.outsideproarts.oassist.core.net.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import java.io.File

class GitHubUpdateTransportTest {
    private val hash = "a".repeat(64)
    private val sha = "b".repeat(40)
    private val assetBase = "https://github.com/outsidepro-arts/OAssist/releases/download/v1.0"
    private fun release(id: Int = 1) = """{"tag_name":"v1.0","prerelease":false,"draft":false,"assets_url":"https://api.github.com/repos/outsidepro-arts/OAssist/releases/$id/assets","body":"notes"}"""
    private fun assets(code: Long = 2, apk: Boolean = true): String {
        val manifest = """{"name":"update-$code.json","browser_download_url":"$assetBase/update-$code.json"}"""
        val binary = """{"name":"app.apk","browser_download_url":"$assetBase/app.apk"}"""
        return if (apk) "[$manifest,$binary]" else "[$manifest]"
    }
    private fun manifest(code: Long = 2) = """{"schemaVersion":1,"applicationId":"ru.outsideproarts.oassist","versionCode":$code,"versionName":"1.0","channel":"stable","commitSha":"$sha","minSdk":31,"abis":[],"certificateSha256":"$hash","apkSha256":"$hash","apkSize":4,"apkName":"app.apk","sourceRef":"refs/tags/v1.0"}"""

    @Test fun paginationAndCompletePairs() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            paths += url
            val body = when {
                url.contains("/releases?") && request.url.parameters["page"] == "1" -> "[" + (1..100).joinToString(",") { release(it) } + "]"
                url.contains("/releases?") -> "[]"
                url.contains("/assets?") -> assets(apk = false)
                url.endsWith("update-2.json") -> manifest()
                else -> error(url)
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }
        assertTrue(GitHubUpdateTransport(client).findCandidates(UpdateChannel.STABLE).isEmpty())
        assertTrue(paths.any { it.contains("/releases?") && it.contains("&page=2") })
        client.close()
    }

    @Test fun errorsAndRateLimitsAreNotEmptyCatalogs() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120"))
        }) { install(HttpTimeout) }
        val transport = GitHubUpdateTransport(client) { 1_000L }
        repeat(2) {
            try { transport.findCandidates(UpdateChannel.STABLE); fail("expected rate limit") }
            catch (e: UpdateRateLimitException) { assertEquals(121_000L, e.retryAtMillis) }
        }
        assertEquals(1, calls)
        client.close()
    }

    @Test fun etagAndCompareStatus() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            if (request.headers[HttpHeaders.IfNoneMatch] != null) respond("", HttpStatusCode.NotModified)
            else respond("""{"status":"ahead"}""", headers = headersOf(HttpHeaders.ETag, "\"tag\""))
        }) { install(HttpTimeout) }
        val transport = GitHubUpdateTransport(client)
        assertTrue(transport.isDescendant(sha, sha))
        assertTrue(transport.isDescendant(sha, sha))
        assertEquals(2, calls)
        client.close()
    }

    @Test fun malformedManifestAndChecksumFail() = runBlocking {
        var malformed = true
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            val body = when {
                url.contains("/releases?") -> "[${release()}]"
                url.contains("/assets?") -> assets()
                url.endsWith("update-2.json") -> if (malformed) "{}" else manifest()
                url.endsWith("app.apk") -> "data"
                else -> error(url)
            }
            respond(body)
        }) { install(HttpTimeout) }
        val transport = GitHubUpdateTransport(client)
        try { transport.findCandidates(UpdateChannel.STABLE); fail("expected invalid manifest") }
        catch (_: UpdateDataException) { }
        malformed = false
        val candidate = transport.findCandidates(UpdateChannel.STABLE).single()
        val dir = kotlin.io.path.createTempDirectory("oassist-update-test").toFile()
        try {
            try { transport.download(candidate, File(dir, "update.apk")); fail("expected checksum failure") }
            catch (_: UpdateDataException) { }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.delete() }
        client.close()
    }
    @Test fun cancellationRemovesPartialFile() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            val body = when {
                url.contains("/releases?") -> "[${release()}]"
                url.contains("/assets?") -> assets()
                url.endsWith("update-2.json") -> manifest()
                url.endsWith("app.apk") -> "data"
                else -> error(url)
            }
            respond(body)
        }) { install(HttpTimeout) }
        val transport = GitHubUpdateTransport(client)
        val candidate = transport.findCandidates(UpdateChannel.STABLE).single()
        val dir = kotlin.io.path.createTempDirectory("oassist-update-cancel").toFile()
        try {
            try {
                transport.download(candidate, File(dir, "update.apk")) { _, _ ->
                    throw kotlinx.coroutines.CancellationException("cancel")
                }
                fail("expected cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.delete() }
        client.close()
    }
    @Test fun rejectedCommitDoesNotReplaceDestinationAndRemovesPart() = runBlocking {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("data".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            respond(when {
                url.contains("/releases?") -> "[${release()}]"
                url.contains("/assets?") -> assets()
                url.endsWith("update-2.json") -> manifest().replace("\"apkSha256\":\"$hash\"", "\"apkSha256\":\"$digest\"")
                url.endsWith("app.apk") -> "data"
                else -> error(url)
            })
        }) { install(HttpTimeout) }
        val transport = GitHubUpdateTransport(client)
        val candidate = transport.findCandidates(UpdateChannel.STABLE).single()
        val dir = kotlin.io.path.createTempDirectory("oassist-update-commit").toFile()
        val target = File(dir, "update.apk").apply { writeText("installer copy") }
        var called = false
        try {
            try {
                transport.download(candidate, target, commit = { part, destination ->
                    called = true
                    assertEquals("data", part.readText())
                    assertEquals(target, destination)
                    throw IllegalStateException("protected")
                })
                fail("expected rejected commit")
            } catch (_: IllegalStateException) { }
            assertTrue(called)
            assertEquals("installer copy", target.readText())
            assertEquals(listOf(target), dir.listFiles()!!.toList())
        } finally {
            target.delete()
            dir.delete()
            client.close()
        }
    }
    @Test fun loadsNewestManifestLazilyAndSkipsInstalledVersions() = runBlocking {
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val body = when {
                url.contains("/releases?") -> "[${release()}]"
                url.contains("/assets?") -> """[{"name":"update-2.json","browser_download_url":"$assetBase/update-2.json"},{"name":"update-3.json","browser_download_url":"$assetBase/update-3.json"},{"name":"app.apk","browser_download_url":"$assetBase/app.apk"}]"""
                url.endsWith("update-3.json") -> manifest(3)
                else -> error(url)
            }
            respond(body)
        }) { install(HttpTimeout) }
        val candidate = GitHubUpdateTransport(client).candidateFlow(UpdateChannel.STABLE, 1).first()
        assertEquals(3L, candidate.manifest.versionCode)
        assertFalse(requested.any { it.endsWith("update-2.json") })
        client.close()
    }
    @Test fun taggedReleaseHistoryUsesEmbeddedAssetsAndLoadsOnlyNewestManifest() = runBlocking {
        val requested = mutableListOf<String>()
        val releases = (1..65).joinToString(",") { code ->
            """{"tag_name":"v$code","prerelease":false,"draft":false,"body":"notes","assets":${assets(code.toLong())}}"""
        }
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val body = when {
                url.contains("/releases?") -> "[$releases]"
                url.endsWith("update-65.json") -> manifest(65)
                else -> error("Unexpected request: $url")
            }
            respond(body)
        }) { install(HttpTimeout) }
        val transport = GitHubUpdateTransport(client)
        assertEquals(65L, transport.candidateFlow(UpdateChannel.STABLE, 0).first().manifest.versionCode)
        assertEquals(2, requested.size)
        assertTrue(transport.candidateFlow(UpdateChannel.STABLE, 65).toList().isEmpty())
        assertEquals(3, requested.size)
        transport.close()
        client.close()
    }
}
