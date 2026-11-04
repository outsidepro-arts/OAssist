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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
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
import ru.outsideproarts.oassist.core.model.modelInfoFor
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderValidator
import ru.outsideproarts.oassist.feature.settings.ProviderSecretsInput

class ProviderSaveFailureTest {
    @Test fun clearingApiKeyValidatesAndCachesAnonymousCatalog() = runTest {
        assertClearedCredentialsValidateAnonymousCatalog(basic = false)
    }

    @Test fun clearingBasicValidatesAnonymousCatalogAndDeletesTrustedPair() = runTest {
        assertClearedCredentialsValidateAnonymousCatalog(basic = true)
    }

    @Test fun rejectedAnonymousValidationRetainsStoredCredentialsAndCatalog() = runTest {
        for (basic in listOf(false, true)) assertClearedCredentialsValidateAnonymousCatalog(basic, denyAnonymous = true)
    }

    private suspend fun assertClearedCredentialsValidateAnonymousCatalog(basic: Boolean, denyAnonymous: Boolean = false) {
        val preferences = MemoryPreferences()
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(preferences), secrets)
        val apiAlias = ProviderRepository.aliasForApiKey("p")
        val userAlias = "trusted.basic.user"
        val passwordAlias = "trusted.basic.password"
        if (basic) secrets.putPair(userAlias, "user".toCharArray(), passwordAlias, "password".toCharArray())
        else secrets.put(apiAlias, "key".toCharArray())
        val profile = ProviderProfile("p", "Provider", ProviderPreset.CUSTOM, "https://old.example/v1",
            authScheme = if (basic) ru.outsideproarts.oassist.core.model.AuthScheme.BASIC else ru.outsideproarts.oassist.core.model.AuthScheme.BEARER,
            apiKeyRef = if (basic) null else apiAlias,
            basicUserRef = if (basic) userAlias else null,
            basicPasswordRef = if (basic) passwordAlias else null)
        repository.upsertProvider(profile)
        val requests = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            val auth = request.headers["Authorization"]
            requests += request.url.host to auth
            if (auth == null && denyAnonymous) return@MockEngine respond("denied", io.ktor.http.HttpStatusCode.Unauthorized)
            respond(if (auth == null) """{"data":[{"id":"public","type":"chat"}]}"""
                else """{"data":[{"id":"private","type":"classification"}]}""",
                headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val authenticated = ModelClient.create(Diagnostics, profile, secrets, http).fetchModels()
            repository.refreshModelCatalog(profile, authenticated)
            val desired = profile.copy(baseUrl = "https://new.example/v1")
            val input = if (basic) ProviderSecretsInput(clearBasic = true) else ProviderSecretsInput(clearApiKey = true)
            val result = saveProvider(repository, ProviderValidator(Diagnostics, secrets, http, null), secrets, desired, input)
            assertTrue(requests.first().second?.startsWith(if (basic) "Basic " else "Bearer ") == true)
            assertEquals("new.example" to null, requests[1])
            if (denyAnonymous) {
                assertTrue(result.isFailure)
                assertEquals(profile, repository.getProvider("p"))
                assertEquals(authenticated, repository.observeModelCatalog("p").first()!!.models)
                assertNotNull(secrets.get(if (basic) userAlias else apiAlias))
                if (basic) assertNotNull(secrets.get(passwordAlias))
                return
            }
            assertTrue(result.toString(), result.isSuccess)
            val stored = repository.getProvider("p")!!
            assertNull(stored.apiKeyRef)
            assertNull(stored.basicUserRef)
            assertNull(stored.basicPasswordRef)
            assertNull(secrets.get(if (basic) userAlias else apiAlias))
            if (basic) assertNull(secrets.get(passwordAlias))
            val reopened = ProviderRepository(ProviderConfigStore(preferences), secrets)
            val catalog = reopened.observeModelCatalog("p").first()!!
            assertEquals(listOf("public"), catalog.models.map { it.id })
            assertTrue(reopened.modelInfo(stored, "public")!!.isChatCompatible)
            assertEquals(catalog.models, reopened.modelsForSelection("p"))
        } finally { http.close() }
    }

    @Test fun everySameAliasCredentialRotationInvalidatesCachedCompatibility() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            val writes: List<suspend (String) -> Unit> = listOf(
                { value -> fixture.repository.setApiKey("p", value.toCharArray()) },
                { value -> fixture.repository.setBasicCredentials("p", "user".toCharArray(), value.toCharArray()); Unit },
                { value -> fixture.repository.setExtraHeader("p", "X-Private", value.toCharArray()) },
            )
            for (write in writes) {
                write("a")
                val old = fixture.repository.getProvider("p")!!
                fixture.repository.refreshModelCatalog(old, listOf(ru.outsideproarts.oassist.core.model.ModelInfo("m", purpose = "classification")))
                write("b")
                val current = fixture.repository.getProvider("p")!!
                assertEquals(old.apiKeyRef, current.apiKeyRef)
                assertEquals(old.basicUserRef, current.basicUserRef)
                assertEquals(old.basicPasswordRef, current.basicPasswordRef)
                assertEquals(old.extraHeaders, current.extraHeaders)
                assertNotEquals(old.credentialRevision, current.credentialRevision)
                assertTrue(fixture.repository.modelsForSelection("p").isEmpty())
                assertNull(fixture.repository.modelInfo(old, "m"))
            }
        } finally { fixture.http.close() }
    }

    @Test fun delayedRefreshWithOldCredentialsCannotOverwriteNewCredentialsCatalog() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            entered.complete(Unit)
            finish.await()
            respond("""{"data":[{"id":"m","type":"classification"}]}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            fixture.seed()
            fixture.repository.setApiKey("p", "a".toCharArray())
            val before = fixture.repository.getProvider("p")!!
            val client = ModelClient.create(Diagnostics, before, fixture.secrets, http)
            val refresh = launch { fixture.repository.refreshModelCatalog(before, client.fetchModels()) }
            entered.await()
            fixture.repository.setApiKey("p", "b".toCharArray())
            val after = fixture.repository.getProvider("p")!!
            assertEquals(before.apiKeyRef, after.apiKeyRef)
            assertNotEquals(before.credentialRevision, after.credentialRevision)
            val models = listOf(ru.outsideproarts.oassist.core.model.ModelInfo("m", purpose = "chat"))
            fixture.repository.refreshModelCatalog(after, models)
            finish.complete(Unit)
            refresh.join()
            assertEquals(models, fixture.repository.modelsForSelection("p"))
            assertEquals(models.single(), fixture.repository.modelInfo(after, "m"))
            assertNull(fixture.repository.modelInfo(before, "m"))
        } finally { http.close(); fixture.http.close() }
    }

    @Test fun newSettingsEntryDoesNotUsePreviousEndpointsCompatibilityAfterPartialSave() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            val old = fixture.repository.getProvider("p")!!
            val restricted = listOf(ru.outsideproarts.oassist.core.model.ModelInfo("m", purpose = "classification",
                supportedGenerationMethods = emptyList()))
            fixture.repository.refreshModelCatalog(old, restricted)
            fixture.secrets.putFailure = IllegalStateException("credential write failed")
            val result = fixture.save(old.copy(baseUrl = "https://new.example/v1"), ProviderSecretsInput(apiKey = "new"))
            assertTrue(result.isFailure)
            val reopened = ProviderRepository(ProviderConfigStore(fixture.preferences), fixture.secrets)
            assertEquals("https://new.example/v1", reopened.getProvider("p")!!.baseUrl)
            assertEquals(restricted, reopened.observeModelCatalog("p").first()!!.models)
            assertTrue(reopened.modelsForSelection("p").isEmpty())
            // Legacy imports retain their IDs, but neither new restriction is trusted.
            reopened.saveModelCatalog("p", restricted)
            val legacy = reopened.modelsForSelection("p").single()
            assertEquals("m", legacy.id)
            assertNull(legacy.purpose)
            assertNull(legacy.supportedGenerationMethods)
            assertTrue(legacy.isChatCompatible)
        } finally { fixture.http.close() }
    }

    @Test fun lateValidationCannotRetagOldCatalogAfterCredentialRotation() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            entered.complete(Unit)
            finish.await()
            respond("""{"data":[{"id":"m","type":"classification"}]}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            fixture.seed()
            fixture.repository.setApiKey("p", "a".toCharArray())
            val before = fixture.repository.getProvider("p")!!
            val job = launch {
                val result = saveProvider(fixture.repository, ProviderValidator(Diagnostics, fixture.secrets, http, null),
                    fixture.secrets, before, ProviderSecretsInput())
                assertTrue(result.exceptionOrNull() is ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException)
            }
            entered.await()
            fixture.repository.setApiKey("p", "b".toCharArray())
            val after = fixture.repository.getProvider("p")!!
            val models = listOf(ru.outsideproarts.oassist.core.model.ModelInfo("m", purpose = "chat"))
            fixture.repository.refreshModelCatalog(after, models)
            finish.complete(Unit)
            job.join()
            assertEquals(models, fixture.repository.modelsForSelection("p"))
            assertEquals(after.credentialRevision, fixture.repository.getProvider("p")!!.credentialRevision)
        } finally { http.close(); fixture.http.close() }
    }

    @Test fun selectedNonChatModelRemainsBlockedAfterValidationSaveAndModeChange() = runTest {
        val store = ProviderConfigStore(MemoryPreferences())
        val secrets = MemorySecrets()
        val repository = ProviderRepository(store, secrets)
        val profile = ProviderProfile("p", "Provider", ProviderPreset.CUSTOM, "https://example.test/v1")
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"data":[{"id":"chat","type":"chat"},{"id":"classifier","type":"classification"}]}""",
                headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val result = saveProvider(repository, ProviderValidator(Diagnostics, secrets, http, null), secrets,
                profile, ProviderSecretsInput(apiKey = "key"))
            assertTrue(result.toString(), result.isSuccess)
            val saved = repository.getProvider("p")!!
            val mode = ru.outsideproarts.oassist.core.model.AssistMode.CHAT
            repository.setModeConfig(repository.modeConfig(mode).copy(providerId = "p", modelId = "chat"))
            repository.setModeConfig(repository.modeConfig(mode).copy(modelId = "classifier"))
            val renamed = saved.copy(name = "Changed name", extraBody = """{"temperature":0.5}""")
            repository.upsertProvider(renamed)
            val client = ModelClient.create(Diagnostics, renamed, secrets, http,
                modelInfo = { id -> repository.observeModelCatalog("p").first()?.modelInfoFor(renamed, id) })
            val selected = repository.modeConfig(mode).modelId!!
            val error = runCatching { client.chatCompletionStream(selected, emptyList(), emptyList(), false).collect() }.exceptionOrNull()
            assertTrue(error is ru.outsideproarts.oassist.core.provider.ProviderError.InvalidInput)
            assertEquals(1, requests)
            assertNull(repository.observeModelCatalog("p").first()?.modelInfoFor(renamed.copy(baseUrl = "https://other.test"), selected))
        } finally { http.close() }
    }

    @Test fun partialBasicPairCanBeRepairedThroughValidationSaveAndHttp() = runTest {
        checkBasicRepair(cleanupFails = false)
    }

    @Test fun basicRepairCleanupFailureKeepsSavedPairAndPendingRetry() = runTest {
        checkBasicRepair(cleanupFails = true)
    }

    private suspend fun checkBasicRepair(cleanupFails: Boolean) {
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val profile = ProviderProfile("p", "Provider", ProviderPreset.CUSTOM, "https://provider.example",
            authScheme = ru.outsideproarts.oassist.core.model.AuthScheme.BASIC, basicUserRef = "legacy.user")
        secrets.put("legacy.user", "old-user".toCharArray())
        if (cleanupFails) secrets.failDeletion = "legacy.user"
        repository.upsertProvider(profile)
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("new-user:new-password".toByteArray())
        val received = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            val auth = request.headers["Authorization"]
            received += auth
            respond("""{"data":[]}""", status = if (auth == expected) io.ktor.http.HttpStatusCode.OK
                else io.ktor.http.HttpStatusCode.Unauthorized, headers = headersOf("Content-Type", "application/json"))
        })
        try {
            var pending = emptySet<String>()
            val result = saveProvider(repository, ProviderValidator(Diagnostics, secrets, http, probeTimeoutMs = null),
                secrets, profile, ProviderSecretsInput(basicUser = "new-user", basicPassword = "new-password"),
                onCleanupPending = { pending = it })
            assertTrue(result.toString(), result.isSuccess)
            val saved = repository.getProvider("p")!!
            assertNotEquals("legacy.user", saved.basicUserRef)
            if (cleanupFails) {
                assertTrue("legacy.user" in pending)
                assertEquals("old-user", secrets.get("legacy.user")?.concatToString())
            } else {
                assertTrue(pending.isEmpty())
                assertNull(secrets.get("legacy.user"))
            }
            ModelClient.create(Diagnostics, saved, secrets, http).fetchModels()
            assertEquals(listOf(expected, expected), received)
            secrets.failDeletion = null
            cleanupProviderHeaders(repository, secrets, pending) { pending = it }
            assertTrue(pending.isEmpty())
            assertNull(secrets.get("legacy.user"))
        } finally { http.close() }
    }

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
        override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) {
            putFailure?.let { throw it }
            if (first == null) values.remove(firstAlias) else values[firstAlias] = first.copyOf()
            if (second == null) values.remove(secondAlias) else values[secondAlias] = second.copyOf()
            first?.fill('\u0000')
            second?.fill('\u0000')
        }
        override suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?> =
            firstAlias?.let { get(it) } to secondAlias?.let { get(it) }
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
