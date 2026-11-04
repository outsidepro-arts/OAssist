package ru.outsideproarts.oassist.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ApprovalState

class ApprovalStoreTest {
    @Test fun replaceAllNeverCommitsAnEmptyIntermediatePolicy() = runBlocking {
        val commits = mutableListOf<Preferences>()
        val dataStore = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                return transform(data.value).also {
                    commits += it
                    data.value = it
                }
            }
        }
        val store = DataStoreApprovalStore(dataStore)
        val old = mapOf("screen:write" to ApprovalState.ALLOW)
        val next = mapOf("screen:write" to ApprovalState.DENY, "mcp:*" to ApprovalState.DENY)
        store.replaceAll(old)
        commits.clear()
        store.replaceAll(next)
        assertEquals(next, store.snapshot())
        assertEquals(1, commits.size)
        assertFalse(commits.single().asMap().isEmpty())
    }
}
