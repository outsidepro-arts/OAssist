package ru.outsideproarts.oassist

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.outsideproarts.oassist.core.data.secret.DataStoreSecretStore
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.data.secret.KeystoreAead
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BasicCredentialsPersistenceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun pairSurvivesClosingAndReopeningRealPreferencesDataStore() = runBlocking {
        val file = directory.root.resolve("basic.preferences_pb")
        val aead = KeystoreAead("basic-test-${UUID.randomUUID()}")
        suspend fun openAndUse(writeFailure: Exception? = null, block: suspend (SecretStore) -> Unit) {
            val owner = SupervisorJob()
            val preferences = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + owner), produceFile = { file },
            )
            val storage = if (writeFailure == null) preferences else object : DataStore<Preferences> by preferences {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    throw writeFailure
                }
            }
            val secrets = DataStoreSecretStore(storage, aead)
            try { block(secrets) } finally { owner.cancelAndJoin() }
        }
        try {
            openAndUse { it.putPair("user", "old-user".toCharArray(), "password", "old-password".toCharArray()) }
            for (failure in listOf(java.io.IOException("commit failed"), CancellationException("commit cancelled"))) {
                try {
                    openAndUse(failure) { it.putPair("user", "new-user".toCharArray(), "password", "new-password".toCharArray()) }
                    fail("Expected commit failure")
                } catch (caught: Exception) { assertEquals(failure, caught) }
                openAndUse {
                    val pair = it.getPair("user", "password")
                    assertEquals("old-user", pair.first?.concatToString())
                    assertEquals("old-password", pair.second?.concatToString())
                }
            }
            openAndUse { it.putPair("user", "new-user".toCharArray(), "password", "new-password".toCharArray()) }
            openAndUse {
                val pair = it.getPair("user", "password")
                assertEquals("new-user", pair.first?.concatToString())
                assertEquals("new-password", pair.second?.concatToString())
            }
        } finally { aead.destroyKey() }
    }
}
