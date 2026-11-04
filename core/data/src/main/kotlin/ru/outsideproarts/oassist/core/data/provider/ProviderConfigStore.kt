package ru.outsideproarts.oassist.core.data.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.catalogFingerprint
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.modelsForSelection

private val Context.providerConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "provider_config")

/**
 * Хранилище конфигурации провайдеров и режимов (JSON в DataStore).
 * Секреты сюда не попадают: только alias-ссылки на SecretStore.
 */
class ProviderConfigStore(private val dataStore: DataStore<Preferences>) {

    private val credentialWrites = Mutex()
    // Fetches may start during a credential write. Their intermediate revision must
    // never become authoritative, including when terminal revision persistence fails.
    private val intermediateRevisions = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun isIntermediate(profile: ProviderProfile): Boolean =
        intermediateRevisions[profile.id]?.let { it == profile.credentialRevision } == true

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

    fun modelsForSelection(providerId: String): Flow<List<ModelInfo>> = dataStore.data.map { prefs ->
        val profile = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }?.firstOrNull { it.id == providerId }
            ?: return@map emptyList()
        if (isIntermediate(profile)) return@map emptyList()
        val catalog = prefs[modelCatalogKey(providerId)]?.let { runCatching { json.decodeFromString<ModelCatalog>(it) }.getOrNull() }
        catalog?.modelsForSelection(profile).orEmpty()
    }

    fun modelInfo(profile: ProviderProfile, modelId: String): Flow<ModelInfo?> = dataStore.data.map { prefs ->
        val current = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }?.firstOrNull { it.id == profile.id }
        if (current?.catalogFingerprint() != profile.catalogFingerprint() ||
            isIntermediate(profile)) return@map null
        val catalog = prefs[modelCatalogKey(profile.id)]?.let { runCatching { json.decodeFromString<ModelCatalog>(it) }.getOrNull() }
        catalog?.takeIf { it.sourceFingerprint == profile.catalogFingerprint() }?.models?.firstOrNull { it.id == modelId }
    }

    suspend fun upsertProviderProfile(profile: ProviderProfile) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }.orEmpty()
            val previous = current.firstOrNull { it.id == profile.id }
            val stored = if (previous == null) profile else profile.copy(credentialRevision = previous.credentialRevision)
            prefs[KEY_PROVIDERS] = json.encodeToString(current.filterNot { it.id == profile.id } + stored)
        }
    }

    internal suspend fun <T> withCredentialMutation(
        providerId: String,
        token: CredentialUpdateToken? = null,
        action: suspend () -> T,
    ): T = credentialWrites.withLock {
        val intermediate = UUID.randomUUID().toString()
        setCredentialRevision(providerId, intermediate, token)
        intermediateRevisions[providerId] = intermediate
        var failure: Throwable? = null
        try {
            action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                withContext(NonCancellable) {
                    val terminal = UUID.randomUUID().toString()
                    setCredentialRevision(providerId, terminal)
                    intermediateRevisions.remove(providerId, intermediate)
                    if (failure == null) token?.revision = terminal
                }
            } catch (error: Throwable) {
                if (failure != null) failure.addSuppressed(error) else throw error
            }
        }
    }

    private suspend fun setCredentialRevision(providerId: String, revision: String, token: CredentialUpdateToken? = null) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }.orEmpty()
            if (token != null && (token.providerId != providerId ||
                current.firstOrNull { it.id == providerId }?.credentialRevision != token.revision)) {
                throw CredentialRevisionMismatchException()
            }
            prefs[KEY_PROVIDERS] = json.encodeToString(current.map {
                if (it.id == providerId) it.copy(credentialRevision = revision) else it
            })
        }
    }

    suspend fun deleteProviderProfile(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }.orEmpty()
            prefs[KEY_PROVIDERS] = json.encodeToString(current.filterNot { it.id == id })
            prefs.remove(modelCatalogKey(id))
        }
        intermediateRevisions.remove(id)
    }

    suspend fun setModeConfig(config: ModeConfig) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_MODES]?.let { decodeList<ModeConfig>(it) }
                ?: AssistMode.entries.map { ModeConfig.default(it) }
            prefs[KEY_MODES] = json.encodeToString(current.filterNot { it.mode == config.mode } + config)
        }
    }

    suspend fun saveModelCatalog(catalog: ModelCatalog, expectedProfile: ProviderProfile? = null, rejectStale: Boolean = false): Unit = credentialWrites.withLock {
        dataStore.edit { prefs ->
            if (expectedProfile != null) {
                val profile = prefs[KEY_PROVIDERS]?.let { decodeList<ProviderProfile>(it) }
                    ?.firstOrNull { it.id == expectedProfile.id }
                if (isIntermediate(expectedProfile) || profile?.catalogFingerprint() != expectedProfile.catalogFingerprint()) {
                    if (rejectStale) throw CredentialRevisionMismatchException()
                    return@edit
                }
            }
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
