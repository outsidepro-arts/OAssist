package ru.outsideproarts.oassist.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.data.mcp.*
import ru.outsideproarts.oassist.core.data.provider.*
import ru.outsideproarts.oassist.core.model.*

class BackupConcurrencyTest {
    @Test fun freshEpochCannotAuthorizeStaleListRowConfiguration() = runTest {
        val secrets = ImportSecrets()
        val repo = McpServerRepository(McpConfigStore(Prefs()), secrets)
        repo.upsert(server)
        repo.importServers(listOf(server.copy(url = "https://imported.invalid")))
        val result = runCatching { repo.saveEditor(server, "old key".toCharArray(), false, repo.editorEpoch(), server) }
        assertTrue(result.exceptionOrNull() is McpEditorInvalidatedException)
        assertEquals("https://imported.invalid", repo.snapshot().single().url)
        assertFalse(secrets.contains(McpServerRepository.aliasForBearer("s")))
    }

    @Test fun oldMcpConnectionCannotResolveSecretsReenteredForImportedUrl() = runTest {
        val secrets = ImportSecrets()
        val repo = McpServerRepository(McpConfigStore(Prefs()), secrets)
        repo.saveEditor(server, "old key".toCharArray(), false, repo.editorEpoch(), null)
        val oldConnection = repo.snapshot().single()
        repo.importServers(listOf(server.copy(url = "https://imported.invalid")))
        repo.saveEditor(repo.snapshot().single(), "new key".toCharArray(), false, repo.editorEpoch(), repo.snapshot().single())
        assertTrue(repo.resolveHeaders(oldConnection).isEmpty())
        assertEquals("Bearer new key", repo.resolveHeaders(repo.snapshot().single())["Authorization"])
    }

    @Test fun providerImportWaitsForActiveCredentialWriteAndClearsItBeforePublishing() = runTest {
        val secrets = ImportSecrets()
        val repo = ProviderRepository(ProviderConfigStore(Prefs()), secrets)
        repo.upsertProvider(provider)
        val token = CredentialUpdateToken("p", repo.getProvider("p")!!.credentialRevision)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        secrets.beforePut = { entered.complete(Unit); release.await() }
        val writer = async { repo.setApiKey("p", "old key".toCharArray(), token) }
        entered.await()
        val importer = async { repo.importProfiles(listOf(provider.copy(baseUrl = "https://imported.invalid"))) }
        yield()
        assertFalse(importer.isCompleted)
        release.complete(Unit)
        writer.await(); importer.await()
        assertNull(repo.getProvider("p")!!.apiKeyRef)
        assertFalse(secrets.contains(ProviderRepository.aliasForApiKey("p")))
        assertEquals("https://imported.invalid", repo.getProvider("p")!!.baseUrl)
    }

    @Test fun queuedCredentialAndConfigWritesCannotRestoreOldProviderAfterImport() = runTest {
        val secrets = ImportSecrets()
        val repo = ProviderRepository(ProviderConfigStore(Prefs()), secrets)
        repo.upsertProvider(provider)
        val token = CredentialUpdateToken("p", repo.getProvider("p")!!.credentialRevision)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        secrets.beforeDelete = { entered.complete(Unit); release.await() }
        val importer = async { repo.importProfiles(listOf(provider.copy(baseUrl = "https://imported.invalid"))) }
        entered.await()
        val writer = async { runCatching { repo.setApiKey("p", "old key".toCharArray(), token) } }
        val editor = async { runCatching { repo.upsertProvider(provider, token) } }
        yield()
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        importer.await()
        assertTrue(writer.await().exceptionOrNull() is CredentialRevisionMismatchException)
        assertTrue(editor.await().exceptionOrNull() is CredentialRevisionMismatchException)
        assertNull(repo.getProvider("p")!!.apiKeyRef)
        assertEquals("https://imported.invalid", repo.getProvider("p")!!.baseUrl)
    }

    @Test fun mcpImportWaitsForActiveEditorAndClearsItsSecret() = runTest {
        val secrets = ImportSecrets()
        val repo = McpServerRepository(McpConfigStore(Prefs()), secrets)
        repo.upsert(server)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        secrets.beforePut = { entered.complete(Unit); release.await() }
        val writer = async { repo.saveEditor(server, "old key".toCharArray(), false, repo.editorEpoch(), server) }
        entered.await()
        val importer = async { repo.importServers(listOf(server.copy(url = "https://imported.invalid"))) }
        yield()
        assertFalse(importer.isCompleted)
        release.complete(Unit)
        writer.await(); importer.await()
        assertEquals("https://imported.invalid", repo.snapshot().single().url)
        assertFalse(repo.snapshot().single().enabled)
        assertTrue(repo.resolveHeaders(repo.snapshot().single()).isEmpty())
        assertFalse(secrets.contains(McpServerRepository.aliasForBearer("s")))
    }

    @Test fun disablingServerBeforeImportLockIsPreserved() = runTest {
        val secrets = ImportSecrets()
        val repo = McpServerRepository(McpConfigStore(Prefs()), secrets)
        repo.upsert(server)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        secrets.beforePut = { entered.complete(Unit); release.await() }
        val editor = async { repo.saveEditor(server.copy(enabled = false), "key".toCharArray(), false, repo.editorEpoch(), server) }
        entered.await()
        val importer = async { repo.importServers(listOf(server)) }
        yield()
        assertFalse(importer.isCompleted)
        release.complete(Unit)
        editor.await()
        importer.await()
        assertFalse(repo.snapshot().single().enabled)
    }

    @Test fun queuedToggleCannotEnableReplacementEvenWhenUrlIsUnchanged() = runTest {
        for (url in listOf(server.url, "https://imported.invalid")) {
            val secrets = ImportSecrets()
            val repo = McpServerRepository(McpConfigStore(Prefs()), secrets)
            val original = server.copy(enabled = false)
            repo.upsert(original)
            val epoch = repo.editorEpoch()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            secrets.beforeDelete = { entered.complete(Unit); release.await() }
            val importer = async { repo.importServers(listOf(server.copy(url = url))) }
            entered.await()
            val toggle = async { runCatching { repo.setEnabled(original, true, epoch) } }
            yield()
            assertFalse(toggle.isCompleted)
            release.complete(Unit)
            importer.await()
            assertTrue(toggle.await().exceptionOrNull() is McpEditorInvalidatedException)
            assertFalse(repo.snapshot().single().enabled)
            if (url != server.url) {
                assertTrue(runCatching { repo.setEnabled(original, true, repo.editorEpoch()) }.exceptionOrNull() is McpEditorInvalidatedException)
            }
            val current = repo.snapshot().single()
            repo.setEnabled(current, true, repo.editorEpoch())
            assertTrue(repo.snapshot().single().enabled)
        }
    }

    @Test fun queuedMcpEditorIsInvalidatedEvenForSameUrlReplacement() = runTest {
        val secrets = ImportSecrets()
        val repo = McpServerRepository(McpConfigStore(Prefs()), secrets)
        repo.upsert(server)
        val oldEpoch = repo.editorEpoch()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        secrets.beforeDelete = { entered.complete(Unit); release.await() }
        val importer = async { repo.importServers(listOf(server)) }
        entered.await()
        val key = "old key".toCharArray()
        val writer = async { runCatching { repo.saveEditor(server, key, false, oldEpoch, server) } }
        yield()
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        importer.await()
        assertTrue(writer.await().exceptionOrNull() is McpEditorInvalidatedException)
        assertTrue(key.all { it == '\u0000' })
        assertFalse(secrets.contains(McpServerRepository.aliasForBearer("s")))
    }

    @Test fun mcpFailedImportAndRecreatedOwnerBothInvalidateOldEditors() = runTest {
        val prefs = Prefs()
        val secrets = ImportSecrets()
        val repo = McpServerRepository(McpConfigStore(prefs), secrets)
        repo.upsert(server)
        val epoch = repo.editorEpoch()
        secrets.beforeDelete = { error("disk failure") }
        assertTrue(runCatching { repo.importServers(listOf(server)) }.isFailure)
        assertTrue(runCatching { repo.saveEditor(server, "key".toCharArray(), false, epoch, server) }.exceptionOrNull() is McpEditorInvalidatedException)
        val freshEpoch = repo.editorEpoch()
        val reopened = McpServerRepository(McpConfigStore(prefs), secrets)
        assertTrue(runCatching { reopened.saveEditor(server, "key".toCharArray(), false, freshEpoch, server) }.exceptionOrNull() is McpEditorInvalidatedException)
        assertFalse(secrets.contains(McpServerRepository.aliasForBearer("s")))
    }

    private class Prefs : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    companion object {
        private val provider = ProviderProfile("p", "P", ProviderPreset.CUSTOM, "https://old.invalid")
        private val server = McpServerConfig("s", "S", "https://old.invalid")
    }
}
