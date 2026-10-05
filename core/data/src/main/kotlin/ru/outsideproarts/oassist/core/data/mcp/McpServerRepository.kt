package ru.outsideproarts.oassist.core.data.mcp

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

    suspend fun upsert(config: McpServerConfig) = store.upsert(config)

    /** Удаляет сервер вместе с его секретами. */
    suspend fun delete(id: String) {
        snapshot().find { it.id == id }?.let { config ->
            config.bearerRef?.let { secrets.delete(it) }
            config.extraHeaders.values.forEach { secrets.delete(it) }
        }
        secrets.delete(aliasForBearer(id))
        store.delete(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = store.setEnabled(id, enabled)

    suspend fun hasBearer(serverId: String): Boolean =
        secrets.contains(aliasForBearer(serverId))

    suspend fun setBearer(serverId: String, value: CharArray) {
        secrets.put(aliasForBearer(serverId), value)
        snapshot().find { it.id == serverId }?.let {
            store.upsert(it.copy(bearerRef = aliasForBearer(serverId)))
        }
    }

    suspend fun clearBearer(serverId: String) {
        secrets.delete(aliasForBearer(serverId))
        snapshot().find { it.id == serverId }?.let {
            store.upsert(it.copy(bearerRef = null))
        }
    }

    /**
     * Резолв заголовков для одного запроса: Bearer + extraHeaders.
     * Plaintext живёт только в возвращаемой карте (паттерн [ProviderAuth]).
     */
    suspend fun resolveHeaders(config: McpServerConfig): Map<String, String> {
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
        return out
    }

    companion object {
        fun aliasForBearer(serverId: String): String = "mcp.$serverId.bearer"
        fun aliasForExtraHeader(serverId: String, headerName: String): String =
            "mcp.$serverId.header.${headerName.lowercase().replace(Regex("[^a-z0-9-]"), "_")}"
    }
}
