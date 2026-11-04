package ru.outsideproarts.oassist.core.data.backup

import kotlinx.coroutines.flow.first
import ru.outsideproarts.oassist.core.data.DataContainer
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.data.mcp.McpServerRepository
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.McpServerConfig
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.model.ToolPreferences

/**
 * Экспорт/импорт/wipe (M5.3). Работает поверх [DataContainer]; секреты через
 * границу бэкапа не переходят (см. [BackupV1]). Импорт и wipe — replace:
 * отсутствующее в бэкапе удаляется с устройства.
 */
class BackupManager(private val data: DataContainer) {

    suspend fun export(): String = encodeBackup(
        BackupV1(
            exportedAtEpochMs = System.currentTimeMillis(),
            language = data.settings.language.first().name,
            providers = data.providers.observeProviders().first()
                .map { it.scrubbed() },
            modes = data.providers.observeModeConfigs().first(),
            catalogs = data.providers.observeProviders().first().mapNotNull { profile ->
                data.providers.observeModelCatalog(profile.id).first()
            },
            toolPrefs = data.toolConfigs.snapshot().toBackup(),
            mcpServers = data.mcpServers.snapshot().map { it.scrubbed() },
            skills = data.skills.list().map { it.toBackup() },
            memory = data.memory.read(),
            approvals = data.approvals.snapshot()
                .filterValues { it != ApprovalState.ASK }
                .mapValues { it.value.name }
                .toSortedMap(),
        ),
    )

    suspend fun import(raw: String): Result<Unit> {
        val backup = decodeBackup(raw).getOrElse { return Result.failure(it) }
        return runCatching {
            // Провайдеры: replace (лишние удалить вместе с секретами).
            data.providers.importProfiles(backup.providers)
            backup.modes.forEach { data.providers.setModeConfig(it) }
            backup.catalogs.forEach { data.providers.saveModelCatalog(it.providerId, it.models) }
            data.toolConfigs.replace(backup.toolPrefs.toDomain())
            // MCP: replace; секреты не импортируются — сбросить ссылки на них.
            data.mcpServers.importServers(backup.mcpServers)
            // Навыки: replace по имени.
            val currentSkills = data.skills.list().map { it.name }.toSet()
            backup.skills.forEach {
                data.skills.upsert(it.name, it.title, it.body)
                data.skills.setEnabled(it.name, it.enabled)
            }
            (currentSkills - backup.skills.map { it.name }.toSet())
                .forEach { data.skills.delete(it) }
            data.memory.write(backup.memory)
            runCatching { data.settings.setLanguage(AppLanguage.valueOf(backup.language)) }
            data.approvals.replaceAll(backup.toApprovalStates())
        }
    }

    /** Полный wipe: конфиги, навыки, память, история, секреты, always-разрешения. */
    suspend fun wipe(): Result<Unit> = try {
        data.providers.observeProviders().first().forEach { data.providers.deleteProvider(it.id) }
        AssistMode.entries.forEach { data.providers.setModeConfig(ModeConfig.default(it)) }
        data.toolConfigs.resetAll()
        data.mcpServers.snapshot().forEach { data.mcpServers.delete(it.id) }
        data.skills.deleteAll()
        data.memory.clear()
        data.approvals.clear()
        data.chats.clearAll()
        data.diagnostics.clear()
        data.secretStore.clearAll()
        data.settings.setLanguage(AppLanguage.DEFAULT)
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal suspend fun ProviderRepository.importProfiles(profiles: List<ProviderProfile>) {
    val current = observeProviders().first().map { it.id }.toSet()
    profiles.forEach {
        // A new imported id is not authority to delete orphaned local aliases.
        if (it.id in current) clearSecrets(it.id)
        upsertProvider(it.scrubbed())
    }
    (current - profiles.map { it.id }.toSet()).forEach { deleteProvider(it) }
}

internal suspend fun McpServerRepository.importServers(servers: List<McpServerConfig>) {
    val current = snapshot().map { it.id }.toSet()
    servers.forEach {
        if (it.id in current) clearSecrets(it.id)
        upsert(it.scrubbed())
    }
    (current - servers.map { it.id }.toSet()).forEach { delete(it) }
}

private fun ToolPreferences.toBackup(): ToolPrefsBackup =
    ToolPrefsBackup(disabled.sorted())

private fun ToolPrefsBackup.toDomain(): ToolPreferences =
    ToolPreferences(disabled.toSet())

/**
 * Запомненные состояния из бэкапа: новые `approvals` (groupKey → состояние)
 * + миграция легаси-списка имён тулов (`alwaysAllowed`) в их группы.
 */
internal fun BackupV1.toApprovalStates(): Map<String, ApprovalState> {
    val states = mutableMapOf<String, ApprovalState>()
    alwaysAllowed.forEach { name ->
        val group = ToolGroups.groupOf(name)
        if (group != ToolGroups.UNKNOWN) states[group] = ApprovalState.ALLOW
    }
    approvals.forEach { (groupKey, raw) ->
        val state = ApprovalState.entries.firstOrNull { it.name == raw } ?: return@forEach
        if (state == ApprovalState.ASK) states.remove(groupKey) else states[groupKey] = state
    }
    return states
}
