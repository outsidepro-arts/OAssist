package ru.outsideproarts.oassist.core.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.outsideproarts.oassist.core.model.McpServerConfig
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.SkillInfo
/**
 * Экспорт/импорт настроек (M5.3). Формат `oassist-backup v1` (JSON).
 *
 * Секреты НЕ экспортируются никогда: Bearer/ключи/пароли/extraHeaders остаются
 * на устройстве, в бэкап уходят только конфиги. После импорта секреты нужно
 * ввести заново (UI так и говорит). Импорт — replace-семантика: отсутствующие
 * в бэкапе записи удаляются.
 */
@Serializable
data class BackupV1(
    val version: Int = VERSION,
    val exportedAtEpochMs: Long = 0L,
    val language: String = "RUSSIAN",
    val providers: List<ProviderProfile> = emptyList(),
    val modes: List<ModeConfig> = emptyList(),
    val catalogs: List<ModelCatalog> = emptyList(),
    val toolPrefs: ToolPrefsBackup = ToolPrefsBackup(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val skills: List<SkillBackup> = emptyList(),
    val memory: String = "",

    /** Запомненные состояния групп разрешений: `groupKey -> "ALLOW"/"DENY"`. */
    val approvals: Map<String, String> = emptyMap(),

    /** Легаси v1 (тулы, не группы): имена с «всегда разрешать» — мигрируются при импорте. */
    val alwaysAllowed: List<String> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class ToolPrefsBackup(
    val disabled: List<String> = emptyList(),
)

@Serializable
data class SkillBackup(
    val name: String,
    val title: String,
    val body: String,
    val enabled: Boolean = true,
)

fun SkillInfo.toBackup(): SkillBackup = SkillBackup(name, title, body, enabled)

/**
 * Стереть ссылки на секреты перед экспортом: значения остаются в Keystore
 * устройства и в бэкап не попадают. Имена extra-заголовков тоже не уходят
 * (без значений они лишь вводят в заблуждение).
 */
internal fun ProviderProfile.scrubbed(): ProviderProfile = copy(
    apiKeyRef = null,
    basicUserRef = null,
    basicPasswordRef = null,
    extraHeaders = emptyMap(),
)

internal fun McpServerConfig.scrubbed(): McpServerConfig = copy(
    bearerRef = null,
    extraHeaders = emptyMap(),
)

private val BackupJson = Json { ignoreUnknownKeys = true }

/** Разобрать и провалидировать бэкап: версия, капы, паттерны. Пустая строка — ошибка. */
fun decodeBackup(raw: String): Result<BackupV1> = runCatching {
    require(raw.length <= MAX_BACKUP_CHARS) { "Файл больше ${MAX_BACKUP_CHARS / 1024} КБ" }
    val parsed = BackupJson.decodeFromString<BackupV1>(raw)
    require(parsed.version == BackupV1.VERSION) { "Неизвестная версия бэкапа: ${parsed.version}" }
    require(parsed.providers.size <= MAX_PROVIDERS) { "Слишком много провайдеров" }
    require(parsed.mcpServers.size <= MAX_MCP) { "Слишком много MCP-серверов" }
    require(parsed.skills.size <= MAX_SKILLS) { "Слишком много навыков" }
    parsed.skills.forEach {
        require(SkillInfo.NAME_PATTERN.matches(it.name)) { "Плохое имя навыка: ${it.name}" }
        require(it.title.length <= MAX_TEXT && it.body.length <= MAX_BODY) { "Навык ${it.name} слишком большой" }
    }
    require(parsed.memory.length <= MAX_MEMORY) { "Память слишком большая" }
    parsed.catalogs.forEach {
        require(it.models.size <= MAX_MODELS) { "Слишком много моделей в каталоге" }
    }
    parsed
}

fun encodeBackup(backup: BackupV1): String = BackupJson.encodeToString(backup)

private const val MAX_BACKUP_CHARS = 2 * 1024 * 1024
private const val MAX_PROVIDERS = 20
private const val MAX_MCP = 20
private const val MAX_SKILLS = 100
private const val MAX_MODELS = 500
private const val MAX_TEXT = 8 * 1024
private const val MAX_BODY = 32 * 1024
private const val MAX_MEMORY = 64 * 1024
