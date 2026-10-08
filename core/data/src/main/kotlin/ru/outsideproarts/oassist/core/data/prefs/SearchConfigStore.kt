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
import ru.outsideproarts.oassist.core.model.SearchEngine

private val Context.searchConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_config")

/** Поисковая система для `web_search`. Device-protected — как прочие настройки тулов. */
class SearchConfigStore(private val context: Context) {

    val engine: Flow<SearchEngine> = context.searchConfigDataStore.data.map { prefs ->
        SearchEngine.fromId(prefs[KEY_ENGINE])
    }

    suspend fun snapshot(): SearchEngine = engine.first()

    suspend fun setEngine(engine: SearchEngine) {
        context.searchConfigDataStore.edit { prefs ->
            prefs[KEY_ENGINE] = engine.id
        }
    }

    private companion object {
        val KEY_ENGINE = stringPreferencesKey("engine")
    }
}