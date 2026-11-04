package ru.outsideproarts.oassist.core.data.backup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
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

    suspend fun export(): String = encodeRestorableBackup(
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

    suspend fun import(raw: String): Result<Unit> = BackupImport(
        data.settings, data.providers, data.toolConfigs, data.mcpServers,
        data.skills, data.memory, data.approvals,
    ).apply(raw)

    /** Полный wipe: конфиги, навыки, память, история, секреты, always-разрешения. */
    suspend fun wipe(): Result<Unit> = data.settings.importMutex.withLock {
        try {
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
            data.settings.setImportUnfinished(false)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Replace spans independent stores; the durable marker survives partial failure. */
internal class BackupImport(
    private val settings: ru.outsideproarts.oassist.core.data.prefs.SettingsDataStore,
    private val providers: ProviderRepository,
    private val toolConfigs: ru.outsideproarts.oassist.core.data.prefs.ToolConfigStore,
    private val mcpServers: McpServerRepository,
    private val skills: ru.outsideproarts.oassist.core.data.skill.SkillStore,
    private val memory: ru.outsideproarts.oassist.core.data.memory.MemoryStore,
    private val approvals: ru.outsideproarts.oassist.core.data.prefs.ApprovalStore,
) {
    suspend fun apply(raw: String): Result<Unit> {
        val backup = decodeBackup(raw).getOrElse { return Result.failure(it) }
        return settings.importMutex.withLock {
            var mutationsStarted = false
            try {
                settings.setImportUnfinished(true)
                mutationsStarted = true
                // Провайдеры: replace (лишние удалить вместе с секретами).
                providers.importProfiles(backup.providers, backup.modes, backup.catalogs)
                toolConfigs.replace(backup.toolPrefs.toDomain())
                // MCP: replace; секреты не импортируются — сбросить ссылки на них.
                mcpServers.importServers(backup.mcpServers)
                // Навыки: replace по имени.
                val currentSkills = skills.list().map { it.name }.toSet()
                backup.skills.forEach {
                    skills.upsert(it.name, it.title, it.body)
                    skills.setEnabled(it.name, it.enabled)
                }
                (currentSkills - backup.skills.map { it.name }.toSet())
                    .forEach { skills.delete(it) }
                memory.write(backup.memory)
                AppLanguage.entries.firstOrNull { it.name == backup.language }?.let { settings.setLanguage(it) }
                approvals.replaceAll(backup.toApprovalStates())
                settings.setImportUnfinished(false)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(if (mutationsStarted) PartialBackupImportException(e) else e)
            }
        }
    }
}

class PartialBackupImportException(cause: Exception) : Exception("Import may be incomplete", cause)

internal suspend fun ProviderRepository.importProfiles(
    profiles: List<ProviderProfile>,
    modes: List<ModeConfig> = emptyList(),
    catalogs: List<ru.outsideproarts.oassist.core.model.ModelCatalog> = emptyList(),
) = replaceFromBackup(profiles.associateBy { it.id }.values.map { it.scrubbed() }, modes, catalogs)

internal suspend fun McpServerRepository.importServers(servers: List<McpServerConfig>) =
    replaceFromBackup(servers.associateBy { it.id }.values.map { it.scrubbed() })

internal fun ToolPreferences.toBackup(): ToolPrefsBackup =
    ToolPrefsBackup(disabled.sorted(), maxToolRounds)

private fun ToolPrefsBackup.toDomain(): ToolPreferences =
    ToolPreferences(disabled.toSet(), maxToolRounds)

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
