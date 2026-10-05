package ru.outsideproarts.oassist.core.data.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ProviderProfile

private val Context.providerConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "provider_config")

/**
 * Хранилище конфигурации провайдеров и режимов (JSON в DataStore).
 * Секреты сюда не попадают: только alias-ссылки на SecretStore.
 */
class ProviderConfigStore(private val dataStore: DataStore<Preferences>) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val providerProfiles: Flow<List<ProviderProfile>> = dataStore.data.map { prefs ->
        prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) } ?: emptyList()
    }

    val modeConfigs: Flow<List<ModeConfig>> = dataStore.data.map { prefs ->
        prefs[KEY_MODES]?.let { decodeList<ModeConfig>(it) }
            ?: AssistMode.entries.map { ModeConfig.default(it) }
    }

    fun modelCatalog(providerId: String): Flow<ModelCatalog?> = dataStore.data.map { prefs ->
        prefs[modelCatalogKey(providerId)]?.let { runCatching { json.decodeFromString<ModelCatalog>(it) }.getOrNull() }
    }

    suspend fun upsertProviderProfile(profile: ProviderProfile) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }.orEmpty()
            prefs[KEY_PROVIDERS] = json.encodeToString(current.filterNot { it.id == profile.id } + profile)
        }
    }

    suspend fun deleteProviderProfile(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }.orEmpty()
            prefs[KEY_PROVIDERS] = json.encodeToString(current.filterNot { it.id == id })
            prefs.remove(modelCatalogKey(id))
        }
    }

    suspend fun setModeConfig(config: ModeConfig) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_MODES]?.let { decodeList<ModeConfig>(it) }
                ?: AssistMode.entries.map { ModeConfig.default(it) }
            prefs[KEY_MODES] = json.encodeToString(current.filterNot { it.mode == config.mode } + config)
        }
    }

    suspend fun saveModelCatalog(catalog: ModelCatalog) {
        dataStore.edit { prefs ->
            prefs[modelCatalogKey(catalog.providerId)] = json.encodeToString(catalog)
        }
    }

    private inline fun <reified T> decodeList(raw: String): List<T>? =
        runCatching { json.decodeFromString<List<T>>(raw) }.getOrNull()

    companion object {
        private val KEY_PROVIDERS = stringPreferencesKey("providers")
        private val KEY_MODES = stringPreferencesKey("modes")

        private fun modelCatalogKey(providerId: String) = stringPreferencesKey("models.$providerId")

        fun from(context: Context): ProviderConfigStore =
            ProviderConfigStore(context.providerConfigDataStore)
    }
}
