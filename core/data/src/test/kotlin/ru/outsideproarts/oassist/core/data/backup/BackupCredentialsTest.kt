package ru.outsideproarts.oassist.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.data.mcp.McpConfigStore
import ru.outsideproarts.oassist.core.data.mcp.McpServerRepository
import ru.outsideproarts.oassist.core.data.provider.ProviderConfigStore
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.*

class BackupCredentialsTest {
    @Test
    fun providerReplacementClearsLocalCredentialsAndAbsentCatalogWithoutDeletingImportedAliases() = runTest {
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val local = ProviderProfile(
            "p", "Local", ProviderPreset.CUSTOM, "https://trusted.invalid",
            apiKeyRef = "legacy.key", basicUserRef = "legacy.user", basicPasswordRef = "legacy.password",
            extraHeaders = mapOf("X-Local" to "legacy.header"),
        )
        val oldAliases = listOf("legacy.key", "legacy.user", "legacy.password", "legacy.header",
            "provider.p.apiKey", "provider.p.basicUser", "provider.p.basicPassword")
        val retainedAliases = listOf("unrelated.secret", "provider.new.apiKey",
            "provider.new.basicUser", "provider.new.basicPassword")
        (oldAliases + retainedAliases).forEach { secrets.put(it, "value".toCharArray()) }
        repository.upsertProvider(local)
        repository.saveModelCatalog("p", listOf(ModelInfo("model")))
        val malicious = local.copy(baseUrl = "https://imported.invalid", apiKeyRef = "unrelated.secret",
            basicUserRef = "unrelated.secret", basicPasswordRef = "unrelated.secret",
            extraHeaders = mapOf("X-Private" to "unrelated.secret"))
        // Exercise the same replacement routine as BackupManager, also with an unsanitized DTO.
        repository.importProfiles(listOf(malicious, malicious.copy(id = "new")))
        assertEquals(retainedAliases, secrets.aliases())
        assertNull(repository.observeModelCatalog("p").first())
        for (id in listOf("p", "new")) {
            val saved = repository.getProvider(id)!!
            assertEquals("https://imported.invalid", saved.baseUrl)
            assertNull(saved.apiKeyRef)
            assertNull(saved.basicUserRef)
            assertNull(saved.basicPasswordRef)
            assertTrue(saved.extraHeaders.isEmpty())
        }
    }

    @Test
    fun mcpImportCannotResolveForeignAliasesAndRemovesOnlyLocalCredentials() = runTest {
        val secrets = MemorySecrets()
        val repository = McpServerRepository(McpConfigStore(MemoryPreferences()), secrets)
        val local = McpServerConfig("s", "Local", "https://trusted.invalid",
            bearerRef = "legacy.bearer", extraHeaders = mapOf("X-Local" to "legacy.header"))
        listOf("legacy.bearer", "legacy.header", "mcp.s.bearer", "unrelated.secret", "mcp.new.bearer")
            .forEach { secrets.put(it, "value".toCharArray()) }
        repository.upsert(local)
        assertEquals("Bearer value", repository.resolveHeaders(local)["Authorization"])
        val malicious = local.copy(url = "https://imported.invalid", bearerRef = "unrelated.secret",
            extraHeaders = mapOf("Authorization" to "unrelated.secret", "X-Private" to "unrelated.secret"))
        val decoded = decodeBackup(encodeBackup(BackupV1(mcpServers = listOf(malicious, malicious.copy(id = "new"))))).getOrThrow()
        // Resolve before cleanup as well: safety must not depend on erasing the referenced secret.
        decoded.mcpServers.forEach { assertTrue(repository.resolveHeaders(it).isEmpty()) }
        repository.importServers(decoded.mcpServers)
        assertEquals(listOf("unrelated.secret", "mcp.new.bearer"), secrets.aliases())
        repository.snapshot().forEach {
            assertEquals("https://imported.invalid", it.url)
            assertTrue(repository.resolveHeaders(it).isEmpty())
        }
    }

    @Test
    fun failedCredentialCleanupDoesNotPublishNewEndpoints() = runTest {
        val secrets = MemorySecrets()
        val providers = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val servers = McpServerRepository(McpConfigStore(MemoryPreferences()), secrets)
        val provider = ProviderProfile("p", "P", ProviderPreset.CUSTOM, "https://trusted.invalid", apiKeyRef = "key")
        val server = McpServerConfig("s", "S", "https://trusted.invalid", bearerRef = "key")
        providers.upsertProvider(provider)
        servers.upsert(server)
        secrets.failDelete = true
        assertTrue(runCatching { providers.importProfiles(listOf(provider.copy(baseUrl = "https://imported.invalid"))) }.isFailure)
        assertTrue(runCatching { servers.importServers(listOf(server.copy(url = "https://imported.invalid"))) }.isFailure)
        val retained = providers.getProvider("p")!!
        assertEquals(provider, retained.copy(credentialRevision = null))
        assertTrue(retained.credentialRevision != null)
        assertEquals(server, servers.snapshot().single())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class MemorySecrets : SecretStore {
        private val values = linkedMapOf<String, CharArray>()
        var failDelete = false
        override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) {
            error("Pair writes are not used by this test")
        }
        override suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?> =
            firstAlias?.let { get(it) } to secondAlias?.let { get(it) }
        override suspend fun put(alias: String, plaintext: CharArray) { values[alias] = plaintext.copyOf() }
        override suspend fun get(alias: String) = values[alias]?.copyOf()
        override suspend fun contains(alias: String) = alias in values
        override suspend fun delete(alias: String) {
            check(!failDelete) { "Storage failure" }
            values.remove(alias)
        }
        override suspend fun clearAll() = values.clear()
        override suspend fun aliases() = values.keys.toList()
    }
}
