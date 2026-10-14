package ru.outsideproarts.oassist.core.provider.modelsdev

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.outsideproarts.oassist.core.provider.opencode.catalogKey

class ModelsDevCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixture = """
        {
          "opencode": {
            "npm": "@ai-sdk/openai-compatible",
            "models": {
              "claude-opus-4-8": {"name": "Claude Opus 4.8", "provider": {"npm": "@ai-sdk/anthropic"}},
              "gpt-5.5": {"name": "GPT-5.5", "provider": {"npm": "@ai-sdk/openai"}},
              "glm-5.3": {"name": "GLM-5.3"}
            }
          },
          "opencode-go": {
            "npm": "@ai-sdk/openai-compatible",
            "models": {
              "qwen3.7-max": {"name": "Qwen3.7 Max", "provider": {"npm": "@ai-sdk/anthropic"}}
            }
          }
        }
    """.trimIndent()

    private class FakeCache(var snapshot: ModelsDevSnapshot? = null, var writes: Int = 0) : ModelsDevCache {
        override suspend fun read(providerKey: String): ModelsDevSnapshot? = snapshot
        override suspend fun write(providerKey: String, snapshot: ModelsDevSnapshot) {
            writes += 1
            this.snapshot = snapshot
        }
    }

    @Test
    fun extractSlice_readsNpmAndNames() {
        val (npm, names) = ModelsDevCatalog.extractSlice(fixture, "opencode")!!

        assertEquals("@ai-sdk/anthropic", npm["claude-opus-4-8"])
        assertEquals("@ai-sdk/openai", npm["gpt-5.5"])
        assertEquals(null, npm["glm-5.3"])
        assertEquals("Claude Opus 4.8", names["claude-opus-4-8"])
        assertEquals("GLM-5.3", names["glm-5.3"])
    }

    @Test
    fun extractSlice_badJson_returnsNull() {
        assertNull(ModelsDevCatalog.extractSlice("<html>oops</html>", "opencode"))
        assertNull(ModelsDevCatalog.extractSlice(fixture, "unknown-provider"))
    }

    @Test
    fun npmFor_freshCache_servesWithoutNetwork() = runTest {
        var fetched = 0
        val engine = MockEngine {
            fetched += 1
            respond(fixture, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val cache = FakeCache(ModelsDevSnapshot(updatedAtEpochMs = 900_000L, npmByModelId = mapOf("m1" to "@ai-sdk/anthropic")))
        val catalog = ModelsDevCatalog(HttpClient(engine), cache, now = { 1_000_000L })

        assertEquals("@ai-sdk/anthropic", catalog.npmFor("opencode", "m1"))
        assertEquals(0, fetched)
    }

    @Test
    fun npmFor_staleCache_refetchesAndPersists() = runBlocking {
        val engine = MockEngine {
            respond(fixture, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val cache = FakeCache(ModelsDevSnapshot(updatedAtEpochMs = 0L, npmByModelId = mapOf("old" to "@ai-sdk/mistral")))
        val catalog = ModelsDevCatalog(HttpClient(engine), cache, now = { ModelsDevCatalog.TTL_MS + 1_000_000L })

        assertEquals("@ai-sdk/anthropic", catalog.npmFor("opencode", "claude-opus-4-8"))
        assertEquals(1, cache.writes)
        assertEquals("@ai-sdk/openai", cache.snapshot?.npmByModelId?.get("gpt-5.5"))
    }

    @Test
    fun npmFor_fetchFails_fallsBackToStaleCache() = runBlocking {
        val engine = MockEngine { respond("oops", status = HttpStatusCode.InternalServerError) }
        val cache = FakeCache(ModelsDevSnapshot(updatedAtEpochMs = 0L, npmByModelId = mapOf("old" to "@ai-sdk/mistral")))
        val catalog = ModelsDevCatalog(HttpClient(engine), cache, now = { ModelsDevCatalog.TTL_MS + 1_000_000L })

        assertEquals("@ai-sdk/mistral", catalog.npmFor("opencode", "old"))
        assertNull(catalog.npmFor("opencode", "claude-opus-4-8"))
        assertEquals(0, cache.writes)
    }

    @Test
    fun fileCache_roundTrips() = runTest {
        val cache = FileModelsDevCache(tmp.root)
        val snapshot = ModelsDevSnapshot(
            updatedAtEpochMs = 42L,
            npmByModelId = mapOf("a" to "@ai-sdk/anthropic"),
            nameByModelId = mapOf("a" to "A"),
        )

        cache.write("opencode", snapshot)
        val read = cache.read("opencode")

        assertEquals(snapshot, read)
    }

    @Test
    fun catalogKey_splitsZenAndGo() {
        assertEquals("opencode", catalogKey("https://opencode.ai/zen/v1"))
        assertEquals("opencode-go", catalogKey("https://opencode.ai/zen/go/v1"))
        assertEquals("opencode", catalogKey("https://opencode.ai/inference/v1"))
        assertEquals("opencode-go", catalogKey("https://opencode.ai/inference/go/v1"))
    }

    @Test
    fun namesFor_returnsLabels() = runBlocking {
        val engine = MockEngine {
            respond(fixture, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }
        val catalog = ModelsDevCatalog(HttpClient(engine), FakeCache(), now = { 1_000_000L })

        val names = catalog.namesFor("opencode")

        assertEquals("GPT-5.5", names["gpt-5.5"])
        assertTrue(names.containsKey("claude-opus-4-8"))
    }
}
