package ru.outsideproarts.oassist.core.data.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.outsideproarts.oassist.core.model.McpServerConfig

private val Context.mcpConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_config")

/**
 * Конфигурация MCP-серверов (JSON в device-protected DataStore).
 * Секреты сюда не попадают: только alias-ссылки на SecretStore.
 */
class McpConfigStore(private val dataStore: DataStore<Preferences>) {

    internal val writes = kotlinx.coroutines.sync.Mutex()
    @Volatile internal var editorEpoch: String = java.util.UUID.randomUUID().toString()

    internal fun invalidateEditors() { editorEpoch = java.util.UUID.randomUUID().toString() }

    internal suspend fun replace(configs: List<McpServerConfig>) {
        dataStore.edit { it[KEY_SERVERS] = json.encodeToString(configs) }
    }

    constructor(context: Context) : this(context.mcpConfigDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val servers: Flow<List<McpServerConfig>> = dataStore.data.map { prefs ->
        read(prefs)
    }

    suspend fun snapshot(): List<McpServerConfig> = servers.first()

    suspend fun upsert(config: McpServerConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVERS] = json.encodeToString(read(prefs).filterNot { it.id == config.id } + config)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVERS] = json.encodeToString(read(prefs).filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVERS] = json.encodeToString(
                read(prefs).map { if (it.id == id) it.copy(enabled = enabled) else it },
            )
        }
    }

    private fun read(prefs: Preferences): List<McpServerConfig> =
        prefs[KEY_SERVERS]?.let { runCatching { json.decodeFromString<List<McpServerConfig>>(it) }.getOrNull() }
            .orEmpty()

    companion object {
        private val KEY_SERVERS = stringPreferencesKey("mcp_servers")
    }
}
