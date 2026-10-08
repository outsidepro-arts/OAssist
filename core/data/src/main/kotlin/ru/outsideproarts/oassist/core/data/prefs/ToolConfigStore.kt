package ru.outsideproarts.oassist.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.outsideproarts.oassist.core.model.ToolPreferences

private val Context.toolConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_config")

/**
 * Включение/выключение инструментов и лимит действий агента.
 * Не секреты — обычное device-protected хранилище, как остальные настройки.
 * Подтверждениями заправляет `ApprovalStore` (группы разрешений).
 */
class ToolConfigStore(private val context: Context) {

    val preferences: Flow<ToolPreferences> = context.toolConfigDataStore.data.map { prefs ->
        val disabled = mutableSetOf<String>()
        prefs.asMap().forEach { (key, value) ->
            val name = key.name
            if (name.startsWith(PREFIX_ENABLED) && value == false) {
                disabled.add(name.removePrefix(PREFIX_ENABLED))
            }
        }
        ToolPreferences(
            disabled = disabled,
            maxToolRounds = prefs[KEY_MAX_TOOL_ROUNDS] ?: ToolPreferences.DEFAULT_MAX_TOOL_ROUNDS,
        )
    }

    suspend fun snapshot(): ToolPreferences = preferences.first()

    suspend fun setToolEnabled(name: String, enabled: Boolean) {
        context.toolConfigDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PREFIX_ENABLED + name)] = enabled
        }
    }

    suspend fun setMaxToolRounds(rounds: Int) {
        context.toolConfigDataStore.edit { prefs ->
            prefs[KEY_MAX_TOOL_ROUNDS] = normalizeMaxToolRounds(rounds)
        }
    }

    /** 0 — без лимита; остальное — в границах [MIN_MAX_TOOL_ROUNDS]..[MAX_MAX_TOOL_ROUNDS]. */
    private fun normalizeMaxToolRounds(rounds: Int): Int =
        if (rounds <= ToolPreferences.UNLIMITED_MAX_TOOL_ROUNDS) {
            ToolPreferences.UNLIMITED_MAX_TOOL_ROUNDS
        } else {
            rounds.coerceIn(MIN_MAX_TOOL_ROUNDS, MAX_MAX_TOOL_ROUNDS)
        }

    /** Сбросить всё к дефолтам (включая легаси-ключи «спрашивать»). */
    suspend fun resetAll() {
        context.toolConfigDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter {
                    it.name.startsWith(PREFIX_ENABLED) ||
                        it.name.startsWith(PREFIX_LEGACY_ASK) ||
                        it == KEY_MAX_TOOL_ROUNDS
                }
                .forEach { prefs.remove(it) }
        }
    }

    /** Replace для импорта: стереть всё и применить снапшот целиком. */
    suspend fun replace(prefs: ToolPreferences) {
        resetAll()
        context.toolConfigDataStore.edit { store ->
            prefs.disabled.forEach { store[booleanPreferencesKey(PREFIX_ENABLED + it)] = false }
            store[KEY_MAX_TOOL_ROUNDS] = normalizeMaxToolRounds(prefs.maxToolRounds)
        }
    }

    companion object {
        private const val PREFIX_ENABLED = "tool.enabled."
        private const val PREFIX_LEGACY_ASK = "tool.ask."
        private val KEY_MAX_TOOL_ROUNDS = intPreferencesKey("tool.max_rounds")
        const val MIN_MAX_TOOL_ROUNDS = 1
        const val MAX_MAX_TOOL_ROUNDS = 100
    }
}
