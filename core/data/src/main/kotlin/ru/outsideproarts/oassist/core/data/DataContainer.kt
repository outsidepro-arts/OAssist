package ru.outsideproarts.oassist.core.data

import android.content.Context
import androidx.room.Room
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.data.mcp.McpConfigStore
import ru.outsideproarts.oassist.core.data.mcp.McpServerRepository
import ru.outsideproarts.oassist.core.data.memory.FileMemoryStore
import ru.outsideproarts.oassist.core.data.memory.MemoryStore
import ru.outsideproarts.oassist.core.data.prefs.SettingsDataStore
import ru.outsideproarts.oassist.core.data.prefs.ToolConfigStore
import ru.outsideproarts.oassist.core.data.prefs.ApprovalStore
import ru.outsideproarts.oassist.core.data.prefs.DataStoreApprovalStore
import ru.outsideproarts.oassist.core.data.skill.FileSkillStore
import ru.outsideproarts.oassist.core.data.skill.SkillStore
import ru.outsideproarts.oassist.core.data.provider.ProviderConfigStore
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.data.secret.DataStoreSecretStore
import ru.outsideproarts.oassist.core.data.secret.KeystoreAead
import ru.outsideproarts.oassist.core.data.secret.SecretStore

/**
 * Сборка слоя данных. Ручной DI, вызывается из AppGraph.
 *
 * [deviceContext] — device-protected storage: секреты и настройки доступны
 * до первой разблокировки (ассистент с экрана блокировки).
 * [credentialContext] — обычное хранилище: история диалогов.
 */
class DataContainer(
    private val credentialContext: Context,
    private val deviceContext: Context,
) {

    val secretStore: SecretStore by lazy {
        DataStoreSecretStore(deviceContext, KeystoreAead())
    }

    val settings: SettingsDataStore by lazy {
        SettingsDataStore(deviceContext)
    }

    val toolConfigs: ToolConfigStore by lazy {
        ToolConfigStore(deviceContext)
    }

    val searchConfig: ru.outsideproarts.oassist.core.data.prefs.SearchConfigStore by lazy {
        ru.outsideproarts.oassist.core.data.prefs.SearchConfigStore(deviceContext)
    }

    val approvals: ApprovalStore by lazy {
        DataStoreApprovalStore(deviceContext)
    }

    val providers: ProviderRepository by lazy {
        ProviderRepository(ProviderConfigStore.from(deviceContext), secretStore)
    }

    val mcpServers: McpServerRepository by lazy {
        McpServerRepository(McpConfigStore(deviceContext), secretStore)
    }

    val memory: MemoryStore by lazy {
        FileMemoryStore(deviceContext)
    }

    val skills: SkillStore by lazy {
        FileSkillStore(deviceContext)
    }

    val database: OAssistDatabase by lazy {
        Room.databaseBuilder(credentialContext, OAssistDatabase::class.java, OAssistDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    fun close() {
        runCatching { database.close() }
    }
}
