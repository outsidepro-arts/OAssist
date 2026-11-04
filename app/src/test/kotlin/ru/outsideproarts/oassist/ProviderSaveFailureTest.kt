package ru.outsideproarts.oassist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.data.provider.ProviderConfigStore
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderValidator
import ru.outsideproarts.oassist.feature.settings.ProviderSecretsInput

class ProviderSaveFailureTest {
    @Test fun failedProfileWriteKeepsOldProfileAndSecrets() = runTest {
        checkFailedProfileWrite(IllegalStateException("write failed"))
    }

    @Test fun cancelledProfileWriteKeepsOldProfileAndSecrets() = runTest {
        checkFailedProfileWrite(CancellationException("cancelled"))
    }

    private suspend fun checkFailedProfileWrite(failure: Exception) {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.preferences.failure = failure
            if (failure is CancellationException) {
                try {
                    fixture.save()
                    fail("Cancellation must propagate")
                } catch (_: CancellationException) { }
            } else {
                assertTrue(fixture.save().isFailure)
            }
            val reopened = fixture.repository.getProvider("p")!!
            assertEquals(fixture.oldProfile, reopened)
            assertEquals("first", fixture.secrets.get("alias.one")?.concatToString())
            assertEquals("second", fixture.secrets.get("alias.two")?.concatToString())
            assertEquals(emptyList<String>(), fixture.secrets.deleted)
            fixture.fetch(reopened)
            assertEquals("first", fixture.requests.last().second)
        } finally { fixture.http.close() }
    }

    @Test fun cleanupFailureAfterOneDeletionKeepsNewProfileOnReopen() = runTest {
        checkCleanupInterrupted(IllegalStateException("delete failed"))
    }

    @Test fun cleanupCancellationAfterOneDeletionKeepsNewProfileOnReopen() = runTest {
        checkCleanupInterrupted(CancellationException("cancelled"))
    }

    private suspend fun checkCleanupInterrupted(failure: Exception) {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.secrets.failDeletion = "alias.two"
            fixture.secrets.deletionFailure = failure
            if (failure is CancellationException) {
                try {
                    fixture.save()
                    fail("Cancellation must propagate")
                } catch (_: CancellationException) { }
            } else {
                assertTrue(fixture.save().isSuccess)
            }
            assertEquals(listOf("alias.one"), fixture.secrets.deleted)
            assertNull(fixture.secrets.get("alias.one"))
            assertNotNull(fixture.secrets.get("alias.two"))
            val reopened = fixture.repository.getProvider("p")!!
            assertEquals(ProviderPreset.OPENAI, reopened.preset)
            assertTrue(reopened.extraHeaders.isEmpty())
            fixture.fetch(reopened)
            assertNull(fixture.requests.last().second)
            assertEquals(setOf("alias.two"), fixture.pending)
            fixture.secrets.failDeletion = null
            cleanupProviderHeaders(fixture.repository, fixture.secrets, fixture.pending) { fixture.pending = it }
            assertTrue(fixture.pending.isEmpty())
            assertNull(fixture.secrets.get("alias.two"))
        } finally { fixture.http.close() }
    }

    @Test fun replacementHeaderReusingAliasIsNotDeleted() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            // This legacy alias is also the canonical alias for X-Private.
            val canonical = ProviderRepository.aliasForExtraHeader("p", "X-Private")
            fixture.repository.upsertProvider(fixture.oldProfile.copy(extraHeaders = mapOf("X-Private" to canonical)))
            fixture.secrets.put(canonical, "old".toCharArray())
            val updated = fixture.oldProfile.copy(extraHeaders = emptyMap())
            val input = ProviderSecretsInput(extraHeaders = mapOf("X-Private" to "new"), clearedExtraHeaders = listOf("X-Private"))
            assertTrue(fixture.save(updated, input).isSuccess)
            val reopened = fixture.repository.getProvider("p")!!
            assertEquals(canonical, reopened.extraHeaders["X-Private"])
            assertEquals("new", fixture.secrets.get(canonical)?.concatToString())
            fixture.fetch(reopened)
            assertEquals("new", fixture.requests.last().second)
        } finally { fixture.http.close() }
    }

    @Test fun cleanupKeepsAliasUsedByAnotherProfile() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            val other = fixture.oldProfile.copy(id = "other", extraHeaders = emptyMap(), apiKeyRef = "alias.one")
            fixture.repository.upsertProvider(other)
            assertTrue(fixture.save().isSuccess)
            assertEquals("first", fixture.secrets.get("alias.one")?.concatToString())
            assertNull(fixture.secrets.get("alias.two"))
            assertEquals(other, fixture.repository.getProvider("other"))
        } finally { fixture.http.close() }
    }

    @Test fun retryDoesNotDeleteAliasThatBecameActive() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.secrets.failDeletion = "alias.two"
            assertTrue(fixture.save().isSuccess)
            val other = fixture.oldProfile.copy(id = "other", extraHeaders = emptyMap(), apiKeyRef = "alias.two")
            fixture.repository.upsertProvider(other)
            fixture.secrets.failDeletion = null
            cleanupProviderHeaders(fixture.repository, fixture.secrets, fixture.pending) { fixture.pending = it }
            assertTrue(fixture.pending.isEmpty())
            assertEquals("second", fixture.secrets.get("alias.two")?.concatToString())
        } finally { fixture.http.close() }
    }

    @Test fun failedNewHeaderWriteRetainsCleanupForSuccessfulRetry() = runTest {
        checkInterruptedHeaderWrite(IllegalStateException("put failed"))
    }

    @Test fun cancelledNewHeaderWriteRetainsCleanupForSuccessfulRetry() = runTest {
        checkInterruptedHeaderWrite(CancellationException("cancelled"))
    }

    private suspend fun checkInterruptedHeaderWrite(failure: Exception) {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.secrets.putFailure = failure
            val input = ProviderSecretsInput(extraHeaders = mapOf("X-New" to "new"), clearedExtraHeaders = listOf("X-Private"))
            if (failure is CancellationException) {
                try {
                    fixture.save(input = input)
                    fail("Cancellation must propagate")
                } catch (_: CancellationException) { }
            } else {
                assertTrue(fixture.save(input = input).isFailure)
            }
            assertEquals(setOf("alias.one"), fixture.pending)
            assertEquals("first", fixture.secrets.get("alias.one")?.concatToString())
            fixture.secrets.putFailure = null
            assertTrue(fixture.save(input = input).isSuccess)
            assertTrue(fixture.pending.isEmpty())
            assertNull(fixture.secrets.get("alias.one"))
            fixture.fetch(fixture.repository.getProvider("p")!!)
            assertNull(fixture.requests.last().second)
        } finally { fixture.http.close() }
    }

    @Test fun delayedCleanupCannotNavigateOrUpdateAfterEditorChanges() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fixture.secrets.beforeDelete = {
                started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
            }
            var currentEditor = true
            var navigations = 0
            var updates = 0
            val job = launch {
                retryProviderHeaderCleanup(fixture.repository, fixture.secrets, setOf("unused"),
                    { currentEditor }, { updates++ }, { navigations++ })
            }
            started.await()
            currentEditor = false // Back or another editor becomes current.
            job.cancel()
            finish.complete(Unit)
            job.join()
            assertEquals(0, navigations)
            assertEquals(0, updates)
        } finally { fixture.http.close() }
    }

    private class Fixture {
        val preferences = MemoryPreferences()
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(preferences), secrets)
        val oldProfile = ProviderProfile("p", "Provider", ProviderPreset.CUSTOM, "https://old.example/v1",
            extraHeaders = linkedMapOf("X-Private" to "alias.one", "X-Other" to "alias.two"))
        val requests = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.host to request.headers["X-Private"]
            respond("""{"data":[]}""", headers = headersOf("Content-Type", "application/json"))
        })
        val validator = ProviderValidator(Diagnostics, secrets, http, probeTimeoutMs = null)
        var pending = emptySet<String>()

        suspend fun seed() {
            secrets.put("alias.one", "first".toCharArray())
            secrets.put("alias.two", "second".toCharArray())
            repository.upsertProvider(oldProfile)
        }
        suspend fun save(
            updated: ProviderProfile = oldProfile.copy(preset = ProviderPreset.OPENAI, baseUrl = "https://new.example/v1", extraHeaders = emptyMap()),
            input: ProviderSecretsInput = ProviderSecretsInput(clearedExtraHeaders = listOf("X-Private", "X-Other")),
        ) = saveProvider(repository, validator, secrets, updated, input, pending) { pending = it }
        suspend fun fetch(profile: ProviderProfile) = ModelClient.create(Diagnostics, profile, secrets, http).fetchModels()
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var failure: Exception? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            failure?.let { throw it }
            return transform(data.value).also { data.value = it }
        }
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        val deleted = mutableListOf<String>()
        var failDeletion: String? = null
        var deletionFailure: Exception = IllegalStateException("delete failed")
        var putFailure: Exception? = null
        var beforeDelete: suspend () -> Unit = {}
        override suspend fun put(alias: String, plaintext: CharArray) {
            putFailure?.let { throw it }
            values[alias] = plaintext.copyOf()
        }
        override suspend fun get(alias: String) = values[alias]?.copyOf()
        override suspend fun contains(alias: String) = alias in values
        override suspend fun delete(alias: String) {
            beforeDelete()
            if (alias == failDeletion) throw deletionFailure
            deleted += alias
            values.remove(alias)
        }
        override suspend fun clearAll() = values.clear()
        override suspend fun aliases() = values.keys.toList()
    }
}
