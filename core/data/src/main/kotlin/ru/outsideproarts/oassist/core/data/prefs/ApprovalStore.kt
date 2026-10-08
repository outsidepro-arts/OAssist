package ru.outsideproarts.oassist.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ToolGroups

private val Context.approvalDataStore: DataStore<Preferences> by preferencesDataStore(name = "approval_config")

/**
 * Запомненные состояния групп разрешений (`groupKey -> ASK/ALLOW/DENY`).
 * Интерфейс — ради фейков в тестах. Device-protected: решения переживают
 * перезагрузку и доступны с экрана блокировки.
 */
interface ApprovalStore {
    val remembered: Flow<Map<String, ApprovalState>>
    suspend fun snapshot(): Map<String, ApprovalState>

    /** Запомнить состояние группы; [ApprovalState.ASK] — убрать запись. */
    suspend fun setRemembered(groupKey: String, state: ApprovalState)

    /** Replace для пресетов/импорта: стереть всё и применить снапшот целиком. */
    suspend fun replaceAll(states: Map<String, ApprovalState>)
    suspend fun clear()
}

class DataStoreApprovalStore(private val context: Context) : ApprovalStore {

    override val remembered: Flow<Map<String, ApprovalState>> =
        context.approvalDataStore.data.map { prefs ->
            val states = mutableMapOf<String, ApprovalState>()
            // Легаси (M2.1–M5.3): approval.always.<toolName> = true → ALLOW группы тула.
            prefs.asMap().forEach { (key, value) ->
                if (key.name.startsWith(PREFIX_LEGACY_ALLOW) && value == true) {
                    val group = ToolGroups.groupOf(key.name.removePrefix(PREFIX_LEGACY_ALLOW))
                    if (group != ToolGroups.UNKNOWN) states[group] = ApprovalState.ALLOW
                }
            }
            prefs.asMap().forEach { (key, value) ->
                if (!key.name.startsWith(PREFIX_STATE)) return@forEach
                val groupKey = key.name.removePrefix(PREFIX_STATE)
                val state = (value as? String)?.let { raw ->
                    ApprovalState.entries.firstOrNull { it.name == raw }
                } ?: return@forEach
                if (state == ApprovalState.ASK) states.remove(groupKey) else states[groupKey] = state
            }
            states
        }

    override suspend fun snapshot(): Map<String, ApprovalState> = remembered.first()

    override suspend fun setRemembered(groupKey: String, state: ApprovalState) {
        context.approvalDataStore.edit { prefs ->
            val key = stringPreferencesKey(PREFIX_STATE + groupKey)
            if (state == ApprovalState.ASK) prefs.remove(key) else prefs[key] = state.name
        }
    }

    override suspend fun replaceAll(states: Map<String, ApprovalState>) {
        clear()
        context.approvalDataStore.edit { prefs ->
            states.filterValues { it != ApprovalState.ASK }.forEach { (groupKey, state) ->
                prefs[stringPreferencesKey(PREFIX_STATE + groupKey)] = state.name
            }
        }
    }

    override suspend fun clear() {
        context.approvalDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter {
                    it.name.startsWith(PREFIX_STATE) || it.name.startsWith(PREFIX_LEGACY_ALLOW)
                }
                .forEach { prefs.remove(it) }
        }
    }

    companion object {
        private const val PREFIX_STATE = "approval.state."
        private const val PREFIX_LEGACY_ALLOW = "approval.always."
    }
}
