package ru.outsideproarts.oassist.core.data.secret

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.core.data.provider.ProviderConfigStore
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.model.Outcome
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class BasicCredentialsTest {
    private var logPriority = 0
    @Before fun suppressAndroidLogging() { logPriority = OAssistLog.minPriority; OAssistLog.minPriority = Int.MAX_VALUE }
    @After fun restoreLogging() { OAssistLog.minPriority = logPriority }
    private val prefs = ControlledPreferences()
    private val config = ControlledPreferences()
    private var encryptionCount = 0
    private var failEncryption: Int? = null
    private var encryptionFailure: Exception = IllegalStateException("encryption failed")
    private var afterFirstDecrypt: (() -> Unit)? = null
    private val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
    private fun cipher(mode: Int, bytes: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(mode, key); doFinal(bytes) }
    private fun secretStore() = DataStoreSecretStore(prefs, { bytes ->
        encryptionCount++
        if (encryptionCount == failEncryption) throw encryptionFailure
        Outcome.Success(cipher(Cipher.ENCRYPT_MODE, bytes))
    }, { bytes ->
        val result = cipher(Cipher.DECRYPT_MODE, bytes)
        afterFirstDecrypt?.also { afterFirstDecrypt = null }?.invoke()
        Outcome.Success(result)
    }, {})
    private val secrets = secretStore()
    private val repository = ProviderRepository(ProviderConfigStore(config), secrets)
    private val profile = ProviderProfile("p", "P", ProviderPreset.CUSTOM, "https://example.invalid")

    private suspend fun seed() {
        repository.upsertProvider(profile)
        repository.setBasicCredentials("p", "old-user".toCharArray(), "old-password".toCharArray())
    }

    private suspend fun assertPair(user: String, password: String, store: SecretStore = secrets) {
        val saved = repository.getProvider("p")!!
        val pair = store.getPair(saved.basicUserRef, saved.basicPasswordRef)
        assertEquals(user, pair.first?.concatToString())
        assertEquals(password, pair.second?.concatToString())
    }

    @Test fun secondEncryptionFailurePreservesOldPairAndClearsInputs() = runTest {
        seed()
        failEncryption = encryptionCount + 2
        val user = "new-user".toCharArray()
        val password = "new-password".toCharArray()
        try {
            repository.setBasicCredentials("p", user, password)
            fail("Expected encryption failure")
        } catch (_: IllegalStateException) { }
        assertTrue(user.all { it == '\u0000' })
        assertTrue(password.all { it == '\u0000' })
        assertPair("old-user", "old-password", secretStore())
    }

    @Test fun cancellationBetweenEncryptionsPreservesOldPair() = runTest {
        seed()
        failEncryption = encryptionCount + 2
        encryptionFailure = CancellationException("cancel between encryptions")
        try {
            repository.setBasicCredentials("p", "new-user".toCharArray(), "new-password".toCharArray())
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertPair("old-user", "old-password")
    }

    @Test fun cancelledCommitAndStorageFailurePreserveOldPair() = runTest {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        prefs.beforeCommit = { entered.complete(Unit); release.await() }
        val job = async { repository.setBasicCredentials("p", "new".toCharArray(), "password".toCharArray()) }
        entered.await()
        job.cancelAndJoin()
        assertPair("old-user", "old-password")
        prefs.beforeCommit = { throw IllegalStateException("storage failed") }
        try {
            repository.setBasicCredentials("p", "new".toCharArray(), "password".toCharArray())
            fail("Expected storage failure")
        } catch (_: IllegalStateException) { }
        assertPair("old-user", "old-password")
    }

    @Test fun readUsesOneSnapshotEvenWhenPairChangesBetweenDecryptions() = runTest {
        seed()
        val oldSnapshot = prefs.current.value
        repository.setBasicCredentials("p", "new-user".toCharArray(), "new-password".toCharArray())
        val newSnapshot = prefs.current.value
        prefs.current.value = oldSnapshot
        afterFirstDecrypt = { prefs.current.value = newSnapshot }
        assertPair("old-user", "old-password")
        assertPair("new-user", "new-password", secretStore())
    }

    @Test fun incompleteReferencesCanBeRepairedAndCleared() = runTest {
        seed()
        val saved = repository.getProvider("p")!!
        repository.upsertProvider(saved.copy(basicPasswordRef = null))
        val retired = repository.setBasicCredentials("p", "repaired-user".toCharArray(), "repaired-password".toCharArray())
        assertPair("repaired-user", "repaired-password")
        assertEquals("old-user", secrets.getPair(saved.basicUserRef, null).first?.concatToString())
        retired.forEach { secrets.delete(it) }
        repository.clearBasicCredentials("p")
        assertNull(repository.getProvider("p")!!.basicUserRef)
        assertNull(repository.getProvider("p")!!.basicPasswordRef)
        assertEquals(null to null, secrets.getPair(saved.basicUserRef, saved.basicPasswordRef))
    }

    @Test fun profilePublicationFailureDoesNotPublishHalfOfFirstPair() = runTest {
        repository.upsertProvider(profile)
        config.beforeCommit = { throw IllegalStateException("profile failed") }
        try {
            repository.setBasicCredentials("p", "user".toCharArray(), "password".toCharArray())
            fail("Expected profile failure")
        } catch (_: IllegalStateException) { }
        assertNull(repository.getProvider("p")!!.basicUserRef)
        assertNull(repository.getProvider("p")!!.basicPasswordRef)
        config.beforeCommit = {}
        repository.setBasicCredentials("p", "retry-user".toCharArray(), "retry-password".toCharArray())
        assertPair("retry-user", "retry-password")
    }

    @Test fun failedPartialRepairPreservesRemainingSecretAndRemovesStaging() = runTest {
        seed()
        val saved = repository.getProvider("p")!!.copy(basicPasswordRef = null)
        repository.upsertProvider(saved)
        val aliasesBefore = secrets.aliases()
        config.beforeCommit = { throw IllegalStateException("profile failed") }
        try {
            repository.setBasicCredentials("p", "new-user".toCharArray(), "new-password".toCharArray())
            fail("Expected publication failure")
        } catch (_: IllegalStateException) { }
        assertEquals(saved, repository.getProvider("p"))
        assertEquals("old-user", secrets.getPair(saved.basicUserRef, null).first?.concatToString())
        assertEquals(aliasesBefore, secrets.aliases())
    }

    @Test fun cancellationDuringRepairPublicationKeepsCommittedPair() = runTest {
        seed()
        repository.upsertProvider(repository.getProvider("p")!!.copy(basicPasswordRef = null))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        config.beforeCommit = { next ->
            if (hasPublishedBasicPair(next)) {
                config.beforeCommit = {}
                entered.complete(Unit)
                release.await()
            }
        }
        val job = async { repository.setBasicCredentials("p", "new-user".toCharArray(), "new-password".toCharArray()) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertPair("new-user", "new-password")
    }

    @Test fun errorAfterRepairPublicationDoesNotDeletePublishedPair() = runTest {
        seed()
        repository.upsertProvider(repository.getProvider("p")!!.copy(basicPasswordRef = null))
        config.afterCommit = { next ->
            if (hasPublishedBasicPair(next)) {
                config.afterCommit = {}
                throw IllegalStateException("late commit failure")
            }
        }
        try {
            repository.setBasicCredentials("p", "new-user".toCharArray(), "new-password".toCharArray())
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertPair("new-user", "new-password")
    }

    private fun hasPublishedBasicPair(preferences: Preferences): Boolean =
        preferences[stringPreferencesKey("providers")]?.let {
            Json.decodeFromString(ListSerializer(ProviderProfile.serializer()), it).single().basicPasswordRef != null
        } == true

    // Tests the production encryption/commit boundary. This is not a disk/reopen test.
    private class ControlledPreferences : DataStore<Preferences> {
        val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow { emit(current.value) }
        var beforeCommit: suspend (Preferences) -> Unit = {}
        var afterCommit: suspend (Preferences) -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(current.value)
            beforeCommit(updated)
            current.value = updated
            afterCommit(updated)
            return updated
        }
    }
}
