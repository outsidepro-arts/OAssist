package ru.outsideproarts.oassist.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.approvalDataStore: DataStore<Preferences> by preferencesDataStore(name = "approval_config")

/**
 * Записи «всегда разрешать» для ASK-инструментов. Интерфейс — ради фейков в тестах.
 * Device-protected: решения переживают перезагрузку и доступны с экрана блокировки.
 */
interface ApprovalStore {
    val alwaysAllowed: Flow<Set<String>>
    suspend fun snapshot(): Set<String>
    suspend fun setAlwaysAllow(toolName: String, allow: Boolean)
    suspend fun replaceAll(toolNames: Set<String>)
    suspend fun clear()
}

class DataStoreApprovalStore(private val context: Context) : ApprovalStore {

    override val alwaysAllowed: Flow<Set<String>> = context.approvalDataStore.data.map { prefs ->
        prefs.asMap()
            .filter { (key, value) -> key.name.startsWith(PREFIX_ALLOW) && value == true }
            .map { (key, _) -> key.name.removePrefix(PREFIX_ALLOW) }
            .toSet()
    }

    override suspend fun snapshot(): Set<String> = alwaysAllowed.first()

    override suspend fun setAlwaysAllow(toolName: String, allow: Boolean) {
        context.approvalDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREFIX_ALLOW + toolName)] = allow
        }
    }

    override suspend fun replaceAll(toolNames: Set<String>) {
        context.approvalDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(PREFIX_ALLOW) }
                .forEach { prefs.remove(it) }
            toolNames.forEach { prefs[booleanPreferencesKey(PREFIX_ALLOW + it)] = true }
        }
    }

    override suspend fun clear() {
        context.approvalDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(PREFIX_ALLOW) }
                .forEach { prefs.remove(it) }
        }
    }

    companion object {
        private const val PREFIX_ALLOW = "approval.always."
    }
}
