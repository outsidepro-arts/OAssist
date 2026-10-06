package ru.outsideproarts.oassist.core.data.backup

import kotlinx.coroutines.flow.first
import ru.outsideproarts.oassist.core.data.DataContainer
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
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
            alwaysAllowed = data.approvals.snapshot().sorted(),
        ),
    )

    suspend fun import(raw: String): Result<Unit> {
        val backup = decodeBackup(raw).getOrElse { return Result.failure(it) }
        return runCatching {
            // Провайдеры: replace (лишние удалить вместе с секретами).
            val currentProviders = data.providers.observeProviders().first().map { it.id }.toSet()
            backup.providers.forEach { data.providers.upsertProvider(it) }
            (currentProviders - backup.providers.map { it.id }.toSet())
                .forEach { data.providers.deleteProvider(it) }
            backup.modes.forEach { data.providers.setModeConfig(it) }
            backup.catalogs.forEach { data.providers.saveModelCatalog(it.providerId, it.models) }
            data.toolConfigs.replace(backup.toolPrefs.toDomain())
            // MCP: replace; секреты не импортируются — сбросить ссылки на них.
            val currentMcp = data.mcpServers.snapshot().map { it.id }.toSet()
            backup.mcpServers.forEach {
                data.mcpServers.upsert(it)
                data.mcpServers.clearBearer(it.id)
            }
            (currentMcp - backup.mcpServers.map { it.id }.toSet())
                .forEach { data.mcpServers.delete(it) }
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
            data.approvals.replaceAll(backup.alwaysAllowed.toSet())
        }
    }

    /** Полный wipe: конфиги, навыки, память, история, секреты, always-разрешения. */
    suspend fun wipe(): Result<Unit> = runCatching {
        data.providers.observeProviders().first().forEach { data.providers.deleteProvider(it.id) }
        AssistMode.entries.forEach { data.providers.setModeConfig(ModeConfig.default(it)) }
        data.toolConfigs.resetAll()
        data.mcpServers.snapshot().forEach { data.mcpServers.delete(it.id) }
        data.skills.list().forEach { data.skills.delete(it.name) }
        data.memory.clear()
        data.approvals.clear()
        data.database.messageDao().deleteAll()
        data.database.sessionDao().deleteAll()
        data.secretStore.clearAll()
        data.settings.setLanguage(AppLanguage.DEFAULT)
    }
}

private fun ToolPreferences.toBackup(): ToolPrefsBackup =
    ToolPrefsBackup(disabled.sorted(), askOverrides.toSortedMap())

private fun ToolPrefsBackup.toDomain(): ToolPreferences =
    ToolPreferences(disabled.toSet(), askOverrides)
