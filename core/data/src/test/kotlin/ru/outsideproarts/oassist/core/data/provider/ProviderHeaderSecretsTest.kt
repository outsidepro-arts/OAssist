package ru.outsideproarts.oassist.core.data.provider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile

class ProviderHeaderSecretsTest {
    private val secrets = MemorySecrets()
    private val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
    private val profile = ProviderProfile("p", "Fixture", ProviderPreset.CUSTOM, "https://example.invalid")

    @Test
    fun distinctHeaderNamesKeepIndependentValuesAfterUpdateAndDeletion() = runTest {
        repository.upsertProvider(profile)
        repository.setExtraHeader("p", "X.Org", "first".toCharArray())
        repository.setExtraHeader("p", "X_Org", "second".toCharArray())
        val refs = repository.getProvider("p")!!.extraHeaders
        assertNotEquals(refs["X.Org"], refs["X_Org"])
        assertEquals("first", secrets.get(refs.getValue("X.Org"))?.concatToString())
        repository.setExtraHeader("p", "X.Org", "updated".toCharArray())
        repository.clearExtraHeader("p", "X.Org")
        assertEquals("second", secrets.get(refs.getValue("X_Org"))?.concatToString())
        assertEquals(setOf("X_Org"), repository.getProvider("p")!!.extraHeaders.keys)
    }

    @Test
    fun existingAliasIsUsedForUpdatesAndDeletion() = runTest {
        val alias = "provider.p.header.x_org"
        repository.upsertProvider(profile.copy(extraHeaders = mapOf("X.Org" to alias)))
        secrets.put(alias, "old".toCharArray())
        repository.setExtraHeader("p", "X.Org", "new".toCharArray())
        assertEquals(alias, repository.getProvider("p")!!.extraHeaders["X.Org"])
        assertEquals("new", secrets.get(alias)?.concatToString())
        repository.clearExtraHeader("p", "X.Org")
        assertNull(secrets.get(alias))
    }

    @Test
    fun deletingOneLegacySharedAliasPreservesTheOtherHeader() = runTest {
        val alias = "provider.p.header.x_org"
        repository.upsertProvider(profile.copy(extraHeaders = mapOf("X.Org" to alias, "X_Org" to alias)))
        secrets.put(alias, "shared".toCharArray())
        repository.clearExtraHeader("p", "X.Org")
        assertEquals("shared", secrets.get(alias)?.concatToString())
        repository.clearExtraHeader("p", "X_Org")
        assertNull(secrets.get(alias))
    }

    @Test
    fun updatingOneLegacySharedAliasDoesNotOverwriteTheOtherHeader() = runTest {
        val alias = "provider.p.header.x_org"
        repository.upsertProvider(profile.copy(extraHeaders = mapOf("X.Org" to alias, "X_Org" to alias)))
        secrets.put(alias, "shared".toCharArray())
        repository.setExtraHeader("p", "X.Org", "new".toCharArray())
        val refs = repository.getProvider("p")!!.extraHeaders
        assertNotEquals(refs["X.Org"], refs["X_Org"])
        assertEquals("shared", secrets.get(refs.getValue("X_Org"))?.concatToString())
        assertEquals("new", secrets.get(refs.getValue("X.Org"))?.concatToString())
    }

    @Test
    fun newlyEncodedAliasDoesNotOverwriteAnExistingLegacyAlias() = runTest {
        val alias = "provider.p.header.x_002eorg"
        repository.upsertProvider(profile.copy(extraHeaders = mapOf("X_002eOrg" to alias)))
        secrets.put(alias, "legacy".toCharArray())
        repository.setExtraHeader("p", "X.Org", "new".toCharArray())
        val refs = repository.getProvider("p")!!.extraHeaders
        assertNotEquals(alias, refs["X.Org"])
        assertEquals("legacy", secrets.get(alias)?.concatToString())
        assertEquals("new", secrets.get(refs.getValue("X.Org"))?.concatToString())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        override suspend fun put(alias: String, plaintext: CharArray) { values[alias] = plaintext.copyOf() }
        override suspend fun get(alias: String) = values[alias]?.copyOf()
        override suspend fun contains(alias: String) = alias in values
        override suspend fun delete(alias: String) { values.remove(alias) }
        override suspend fun clearAll() = values.clear()
        override suspend fun aliases() = values.keys.toList()
    }
}
