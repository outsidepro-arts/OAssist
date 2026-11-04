package ru.outsideproarts.oassist.core.data.skill

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.outsideproarts.oassist.core.data.atomicWriteText
import ru.outsideproarts.oassist.core.model.SkillInfo
import java.io.File
import java.io.IOException

private val Context.skillConfigDataStore by preferencesDataStore(name = "skill_config")

/**
 * Навыки агента: папки `skills/<name>/SKILL.md` + вкл/выкл в DataStore.
 * device-protected storage — доступно с экрана блокировки, как остальным
 * настройкам. Интерфейс — для фейков в тестах тулов; реализация — файлы.
 */
interface SkillStore {
    suspend fun list(): List<SkillInfo>
    suspend fun get(name: String): SkillInfo?
    suspend fun upsert(name: String, title: String, body: String)
    suspend fun setEnabled(name: String, enabled: Boolean)
    suspend fun delete(name: String)
    suspend fun deleteAll() { list().forEach { delete(it.name) } }
}

class FileSkillStore internal constructor(
    private val filesDir: () -> File,
    private val config: DataStore<Preferences>,
    private val deleteDirectory: (File) -> Boolean = File::deleteRecursively,
) : SkillStore {

    constructor(deviceContext: Context) : this({ deviceContext.filesDir }, deviceContext.skillConfigDataStore)

    private fun dir(): File {
        val dir = File(filesDir(), "skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun skillDir(name: String): File = File(dir(), name)

    private suspend fun isEnabled(name: String): Boolean =
        config.data.map { prefs ->
            prefs[booleanPreferencesKey(KEY_PREFIX + name)] ?: true
        }.first()

    private fun readSkill(skillDir: File, enabled: Boolean): SkillInfo? {
        val file = File(skillDir, SkillInfo.FILE_NAME)
        if (!file.exists()) return null
        val body = runCatching { file.readText().trim() }.getOrNull() ?: return null
        if (body.isEmpty()) return null
        val title = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: skillDir.name
        return SkillInfo(name = skillDir.name, title = title, body = body, enabled = enabled)
    }

    override suspend fun list(): List<SkillInfo> {
        val dirs = runCatching {
            dir().listFiles { f -> f.isDirectory }?.toList().orEmpty()
        }.getOrElse { emptyList() }
        return dirs.mapNotNull { skillDir ->
            readSkill(skillDir, isEnabled(skillDir.name))
        }.sortedBy { it.name }
    }

    override suspend fun get(name: String): SkillInfo? {
        if (!SkillInfo.NAME_PATTERN.matches(name)) return null
        val skillDir = skillDir(name)
        if (!skillDir.isDirectory) return null
        return readSkill(skillDir, isEnabled(name))
    }

    override suspend fun upsert(name: String, title: String, body: String) {
        require(SkillInfo.NAME_PATTERN.matches(name)) { "bad skill name: $name" }
        val cleanTitle = title.trim().ifEmpty { name }
        val cleanBody = body.trim()
        require(cleanBody.isNotEmpty()) { "empty skill body" }
        val skillDir = skillDir(name)
        if (!skillDir.exists()) skillDir.mkdirs()
        val text = if (cleanBody.lineSequence().any { it.trim().startsWith("# ") }) {
            cleanBody
        } else {
            "# $cleanTitle\n\n$cleanBody"
        }
        atomicWriteText(File(skillDir, SkillInfo.FILE_NAME), text.trim() + "\n")
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) {
        config.edit { prefs ->
            prefs[booleanPreferencesKey(KEY_PREFIX + name)] = enabled
        }
    }

    override suspend fun delete(name: String) {
        if (!SkillInfo.NAME_PATTERN.matches(name)) return
        if (!deleteDirectory(skillDir(name))) throw IOException("Cannot delete skill")
        config.edit { prefs ->
            prefs.remove(booleanPreferencesKey(KEY_PREFIX + name))
        }
    }

    override suspend fun deleteAll() {
        val root = dir()
        val entries = root.listFiles() ?: throw IOException("Cannot list skills")
        for (entry in entries) {
            if (!SkillInfo.NAME_PATTERN.matches(entry.name)) throw IOException("Invalid skill entry")
            delete(entry.name)
        }
        config.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(KEY_PREFIX) }.forEach { prefs.remove(it) }
        }
    }

    companion object {
        private const val KEY_PREFIX = "skill.enabled."
    }
}
