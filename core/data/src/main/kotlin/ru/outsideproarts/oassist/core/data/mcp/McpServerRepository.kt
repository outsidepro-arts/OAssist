package ru.outsideproarts.oassist.core.data.mcp

import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.flow.Flow
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.McpServerConfig

/**
 * Репозиторий MCP-серверов: конфиг + секреты через [SecretStore]
 * (та же alias-схема, что у провайдеров).
 */
class McpServerRepository(
    private val store: McpConfigStore,
    private val secrets: SecretStore,
) {

    fun observeServers(): Flow<List<McpServerConfig>> = store.servers

    suspend fun snapshot(): List<McpServerConfig> = store.snapshot()

    suspend fun upsert(config: McpServerConfig) = store.writes.withLock { store.upsert(config) }

    fun editorEpoch(): String = store.editorEpoch

    /** A restored or queued editor cannot reintroduce credentials after replacement. */
    suspend fun saveEditor(
        config: McpServerConfig, bearer: CharArray?, clearBearer: Boolean,
        expectedEpoch: String, original: McpServerConfig?,
    ) {
        try {
            store.writes.withLock {
                if (expectedEpoch != store.editorEpoch || snapshot().firstOrNull { it.id == config.id } != original) {
                    throw McpEditorInvalidatedException()
                }
                store.upsert(config)
                var saved = config
                when {
                    clearBearer -> {
                        secrets.delete(aliasForBearer(config.id))
                        saved = saved.copy(bearerRef = null)
                    }
                    bearer != null -> {
                        secrets.put(aliasForBearer(config.id), bearer)
                        saved = saved.copy(bearerRef = aliasForBearer(config.id))
                    }
                }
                store.upsert(saved)
            }
        } finally {
            bearer?.fill('\u0000')
        }
    }

    internal suspend fun replaceFromBackup(configs: List<McpServerConfig>) = store.writes.withLock {
        store.invalidateEditors()
        snapshot().forEach { deleteSecrets(it.id, it) }
        store.replace(configs)
    }

    /** Удаляет сервер вместе с его секретами. */
    suspend fun delete(id: String) = store.writes.withLock {
        store.invalidateEditors()
        deleteSecrets(id, snapshot().find { it.id == id })
        store.delete(id)
    }

    /** Clears credentials using only the current local config, before backup replacement. */
    suspend fun clearSecrets(id: String) = store.writes.withLock {
        store.invalidateEditors()
        deleteSecrets(id, snapshot().find { it.id == id })
        snapshot().find { it.id == id }?.let {
            store.upsert(it.copy(bearerRef = null, extraHeaders = emptyMap()))
        }
    }

    private suspend fun deleteSecrets(id: String, config: McpServerConfig?) {
        config?.let {
            config.bearerRef?.let { secrets.delete(it) }
            config.extraHeaders.values.forEach { secrets.delete(it) }
        }
        secrets.delete(aliasForBearer(id))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = store.writes.withLock { store.setEnabled(id, enabled) }

    suspend fun hasBearer(serverId: String): Boolean =
        secrets.contains(aliasForBearer(serverId))

    /**
     * Резолв заголовков для одного запроса: Bearer + extraHeaders.
     * Plaintext живёт только в возвращаемой карте (паттерн [ProviderAuth]).
     */
    suspend fun resolveHeaders(config: McpServerConfig): Map<String, String> = store.writes.withLock {
        // A connection opened before replace must not resolve newly entered secrets
        // using its old URL and the same stable alias.
        if (snapshot().firstOrNull { it.id == config.id } != config) return@withLock emptyMap()
        val out = LinkedHashMap<String, String>()
        config.bearerRef?.let { alias ->
            secrets.get(alias)?.let { token ->
                try {
                    out["Authorization"] = "Bearer " + String(token)
                } finally {
                    token.fill('\u0000')
                }
            }
        }
        config.extraHeaders.forEach { (name, alias) ->
            secrets.get(alias)?.let { value ->
                try {
                    out[name] = String(value)
                } finally {
                    value.fill('\u0000')
                }
            }
        }
        out
    }

    companion object {
        fun aliasForBearer(serverId: String): String = "mcp.$serverId.bearer"
        fun aliasForExtraHeader(serverId: String, headerName: String): String =
            "mcp.$serverId.header.${headerName.lowercase().replace(Regex("[^a-z0-9-]"), "_")}"
    }
}

class McpEditorInvalidatedException : IllegalStateException("MCP configuration was replaced")
