package ru.outsideproarts.oassist.core.model

/**
 * Навык агента: папка `skills/<name>/SKILL.md` + вкл/выкл в DataStore.
 * В контекст подставляются только включённые (см. PromptRenderer);
 * целиком тело отдаёт тул `skill_get`.
 */
data class SkillInfo(
    /** Идентификатор = имя папки, `[a-z0-9_-]+`. */
    val name: String,
    /** Первая `# `-строка SKILL.md либо имя. */
    val title: String,
    /** Полное тело SKILL.md. */
    val body: String,
    val enabled: Boolean = true,
) {
    companion object {
        val NAME_PATTERN = Regex("^[a-z0-9_-]{1,64}$")
        const val FILE_NAME = "SKILL.md"
    }
}
