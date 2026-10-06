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
import ru.outsideproarts.oassist.core.model.ToolPreferences

private val Context.toolConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_config")

/**
 * Включение/выключение инструментов и пер-тул «спрашивать перед выполнением».
 * Не секреты — обычное device-protected хранилище, как остальные настройки.
 */
class ToolConfigStore(private val context: Context) {

    val preferences: Flow<ToolPreferences> = context.toolConfigDataStore.data.map { prefs ->
        val disabled = mutableSetOf<String>()
        val askOverrides = mutableMapOf<String, Boolean>()
        prefs.asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name.startsWith(PREFIX_ENABLED) && value == false ->
                    disabled.add(name.removePrefix(PREFIX_ENABLED))
                name.startsWith(PREFIX_ASK) && value is Boolean ->
                    askOverrides[name.removePrefix(PREFIX_ASK)] = value
            }
        }
        ToolPreferences(disabled = disabled, askOverrides = askOverrides)
    }

    suspend fun snapshot(): ToolPreferences = preferences.first()

    suspend fun setToolEnabled(name: String, enabled: Boolean) {
        context.toolConfigDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREFIX_ENABLED + name)] = enabled
        }
    }

    suspend fun setAskBefore(name: String, ask: Boolean) {
        context.toolConfigDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREFIX_ASK + name)] = ask
        }
    }

    /** Сбросить всё к дефолтам: убрать записи включения и переопределения «спрашивать». */
    suspend fun resetAll() {
        context.toolConfigDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(PREFIX_ENABLED) || it.name.startsWith(PREFIX_ASK) }
                .forEach { prefs.remove(it) }
        }
    }

    /** Replace для импорта: стереть всё и применить снапшот целиком. */
    suspend fun replace(prefs: ToolPreferences) {
        resetAll()
        context.toolConfigDataStore.edit { store ->
            prefs.disabled.forEach { store[booleanPreferencesKey(PREFIX_ENABLED + it)] = false }
            prefs.askOverrides.forEach { (name, ask) ->
                store[booleanPreferencesKey(PREFIX_ASK + name)] = ask
            }
        }
    }

    companion object {
        private const val PREFIX_ENABLED = "tool.enabled."
        private const val PREFIX_ASK = "tool.ask."
    }
}
