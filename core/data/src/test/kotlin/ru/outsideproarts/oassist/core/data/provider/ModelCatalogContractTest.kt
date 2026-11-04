package ru.outsideproarts.oassist.core.data.provider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelCatalogContractTest {
    private val profile = ProviderProfile("p", "Provider", ProviderPreset.CUSTOM, "https://a.test/v1")

    @Test
    fun tokenAdvancesOnlyForItsOwnCompletedWritesAndCannotPublishAfterForeignRotation() = runTest {
        val store = ProviderConfigStore(MemoryPreferences())
        store.upsertProviderProfile(profile)
        val token = CredentialUpdateToken("p", null)
        store.withCredentialMutation("p", token) {}
        val committed = token.revision
        assertNotNull(committed)
        assertEquals(store.providerProfiles.first().single().credentialRevision, committed)
        store.withCredentialMutation("p") {} // Another owner rotates the same aliases.
        var actionRan = false
        assertTrue(runCatching { store.withCredentialMutation("p", token) { actionRan = true } }.exceptionOrNull() is CredentialRevisionMismatchException)
        assertFalse(actionRan)
        assertEquals(committed, token.revision)
        val stale = profile.copy(credentialRevision = committed)
        val catalog = ModelCatalog("p", 1, listOf(ModelInfo("m")), stale.catalogFingerprint())
        assertTrue(runCatching { store.saveModelCatalog(catalog, stale, rejectStale = true) }.exceptionOrNull() is CredentialRevisionMismatchException)
        assertNull(store.modelCatalog("p").first())
        val current = store.providerProfiles.first().single()
        val failed = CredentialUpdateToken("p", current.credentialRevision)
        val original = failed.revision
        assertTrue(runCatching { store.withCredentialMutation("p", failed) { throw CancellationException("cancel") } }.isFailure)
        assertEquals(original, failed.revision)
        assertNotEquals(original, store.providerProfiles.first().single().credentialRevision)
    }

    @Test
    fun intermediateFetchCannotPublishAndCredentialWritesAreSerialized() = runTest {
        val store = ProviderConfigStore(MemoryPreferences())
        store.upsertProviderProfile(profile)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var secondEntered = false
        val first = launch { store.withCredentialMutation("p") { started.complete(Unit); finish.await() } }
        started.await()
        val intermediate = store.providerProfiles.first().single()
        val pendingCatalog = ModelCatalog("p", 1, listOf(ModelInfo("m", purpose = "classification")), intermediate.catalogFingerprint())
        val publish = launch { store.saveModelCatalog(pendingCatalog, intermediate) }
        val second = launch { store.withCredentialMutation("p") { secondEntered = true } }
        runCurrent()
        assertFalse(publish.isCompleted)
        assertFalse(secondEntered)
        finish.complete(Unit)
        first.join(); publish.join(); second.join()
        assertTrue(secondEntered)
        assertNull(store.modelCatalog("p").first())
        val current = store.providerProfiles.first().single()
        assertNotEquals(intermediate.credentialRevision, current.credentialRevision)
        store.upsertProviderProfile(profile.copy(name = "Stale editor"))
        assertEquals(current.credentialRevision, store.providerProfiles.first().single().credentialRevision)
        val fresh = pendingCatalog.copy(sourceFingerprint = current.catalogFingerprint())
        store.saveModelCatalog(fresh, current)
        assertEquals(fresh.models, store.modelsForSelection("p").first())
    }

    @Test
    fun mutationFailureAndCancellationInvalidateWithoutHoldingMutex() = runTest {
        val store = ProviderConfigStore(MemoryPreferences())
        store.upsertProviderProfile(profile)
        var previous = profile
        for (error in listOf(IllegalStateException("write failed"), CancellationException("cancelled"))) {
            val catalog = ModelCatalog("p", 1, listOf(ModelInfo("m", purpose = "chat")), previous.catalogFingerprint())
            store.saveModelCatalog(catalog, previous)
            assertSame(error, runCatching { store.withCredentialMutation("p") { throw error } }.exceptionOrNull())
            val current = store.providerProfiles.first().single()
            assertNotEquals(previous.credentialRevision, current.credentialRevision)
            assertTrue(store.modelsForSelection("p").first().isEmpty())
            store.saveModelCatalog(catalog.copy(sourceFingerprint = current.catalogFingerprint()), current)
            assertEquals(catalog.models, store.modelsForSelection("p").first())
            previous = current
        }
    }

    @Test
    fun beginAndTerminalPersistenceFailuresDoNotAuthorizeIntermediateCatalogs() = runTest {
        val prefs = MemoryPreferences()
        val store = ProviderConfigStore(prefs)
        store.upsertProviderProfile(profile)
        prefs.failOnUpdate = prefs.updates + 1
        var actionRan = false
        assertTrue(runCatching { store.withCredentialMutation("p") { actionRan = true } }.isFailure)
        assertFalse(actionRan)
        assertEquals(profile, store.providerProfiles.first().single())
        prefs.failOnUpdate = prefs.updates + 2
        assertTrue(runCatching { store.withCredentialMutation("p") {} }.isFailure)
        val failedTerminal = store.providerProfiles.first().single()
        prefs.failOnUpdate = null
        val catalog = ModelCatalog("p", 1, listOf(ModelInfo("m")), failedTerminal.catalogFingerprint())
        store.saveModelCatalog(catalog, failedTerminal)
        assertNull(store.modelCatalog("p").first())
        store.withCredentialMutation("p") {}
        val recovered = store.providerProfiles.first().single()
        store.saveModelCatalog(catalog.copy(sourceFingerprint = recovered.catalogFingerprint()), recovered)
        assertEquals(catalog.models, store.modelsForSelection("p").first())
    }

    @Test
    fun metadataRoundTripsAndSurvivesManualEditsAndRefresh() = runTest {
        val prefs = MemoryPreferences()
        val store = ProviderConfigStore(prefs)
        store.upsertProviderProfile(profile)
        val model = ModelInfo("m", purpose = "classification", supportedGenerationMethods = emptyList())
        val catalog = ModelCatalog("p", 1, listOf(model), profile.catalogFingerprint())
        store.saveModelCatalog(catalog, profile)
        val reopened = ProviderConfigStore(prefs).modelCatalog("p").first()!!
        assertEquals(catalog, reopened)
        assertFalse(reopened.modelInfoFor(profile, "m")!!.isChatCompatible)
        val patched = patchModelProtocol(patchModelCapabilities(reopened, "p", "m", setOf(ModelCapability.TOOLS), 2),
            "p", "m", ModelProtocol.OPENAI_COMPLETIONS, 3)
        assertFalse(patched.modelInfoFor(profile, "m")!!.isChatCompatible)
        val refreshed = mergeModelCatalog(patched, "p", listOf(model.copy(purpose = "chat", supportedGenerationMethods = null)), 4)
        assertTrue(refreshed.models.single().isChatCompatible)
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, refreshed.models.single().protocol)
        assertEquals(setOf(ModelCapability.TOOLS), refreshed.models.single().capabilities)
        val legacy = Json.decodeFromString(ModelCatalog.serializer(), """{"providerId":"p","updatedAtEpochMs":1,"models":[{"id":"m"}]}""")
        assertTrue(legacy.models.single().isChatCompatible)
        assertNull(legacy.modelInfoFor(profile, "m"))
    }

    @Test
    fun connectionIdentityRejectsOldEndpointAndLateCatalogPublication() = runTest {
        val store = ProviderConfigStore(MemoryPreferences())
        store.upsertProviderProfile(profile)
        val old = ModelCatalog("p", 1, listOf(ModelInfo("m", purpose = "classification")), profile.catalogFingerprint())
        store.saveModelCatalog(old, profile)
        val cosmetic = profile.copy(name = "Renamed", extraBody = """{"temperature":0.5}""", enabled = false)
        assertEquals(profile.catalogFingerprint(), cosmetic.catalogFingerprint())
        assertFalse(old.modelInfoFor(cosmetic, "m")!!.isChatCompatible)
        val changed = profile.copy(baseUrl = "https://b.test/v1")
        assertNull(old.modelInfoFor(changed, "m"))
        assertNull(old.modelInfoFor(profile.copy(catalogPath = "custom/models"), "m"))
        assertNull(old.modelInfoFor(profile.copy(authScheme = AuthScheme.BASIC), "m"))
        store.upsertProviderProfile(changed)
        val fresh = ModelCatalog("p", 2, listOf(ModelInfo("m", purpose = "chat")), changed.catalogFingerprint())
        store.saveModelCatalog(fresh, changed)
        store.saveModelCatalog(old, profile)
        assertEquals(fresh, store.modelCatalog("p").first())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        var updates = 0
        var failOnUpdate: Int? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            if (++updates == failOnUpdate) error("preferences write failed")
            transform(data.value).also { data.value = it }
        }
    }
}
