package ru.outsideproarts.oassist.core.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import ru.outsideproarts.oassist.core.model.update.UpdatePreferences

class UpdatePreferencesDataStoreTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun settingsArePersistedAndManualChannelCanBeCleared() = runTest {
        val storage = MemoryStore()
        val store = UpdatePreferencesDataStore(storage)
        assertEquals(UpdatePreferences(), store.preferences.first())
        store.setAutomaticChecks(false)
        store.setNotifications(true)
        store.setChannel(UpdateChannel.PRERELEASE)
        assertEquals(UpdatePreferences(false, true, UpdateChannel.PRERELEASE), UpdatePreferencesDataStore(storage).preferences.first())
        store.setChannel(null)
        assertEquals(UpdatePreferences(false, true, null), store.preferences.first())
    }
}
