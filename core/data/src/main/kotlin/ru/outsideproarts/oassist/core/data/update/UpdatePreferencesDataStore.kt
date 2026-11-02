package ru.outsideproarts.oassist.core.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import ru.outsideproarts.oassist.core.model.update.UpdatePreferences

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_preferences")

class UpdatePreferencesDataStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.updateDataStore)

    val preferences: Flow<UpdatePreferences> = store.data.map { prefs ->
        UpdatePreferences(
            automaticChecks = prefs[AUTOMATIC] ?: true,
            notifications = prefs[NOTIFICATIONS] ?: false,
            manualChannel = prefs[CHANNEL]?.let { raw -> UpdateChannel.entries.find { it.name == raw } },
        )
    }

    suspend fun setAutomaticChecks(enabled: Boolean) { store.edit { it[AUTOMATIC] = enabled } }
    suspend fun setNotifications(enabled: Boolean) { store.edit { it[NOTIFICATIONS] = enabled } }
    suspend fun setChannel(channel: UpdateChannel?) {
        store.edit { prefs ->
            if (channel == null) prefs.remove(CHANNEL) else prefs[CHANNEL] = channel.name
        }
    }

    private companion object {
        val AUTOMATIC = booleanPreferencesKey("automatic_checks")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val CHANNEL = stringPreferencesKey("manual_channel")
    }
}
