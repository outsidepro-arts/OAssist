package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.SkillInfo

/**
 * Входные для системного промпта. Пустые/blank — подставляются пустой строкой.
 */
data class PromptInputs(
    val locale: String = "",
    val time: String = "",
    val device: String = "",
    val memory: String = "",
    val skills: List<SkillInfo> = emptyList(),
    val screenContext: String? = null,
    val sharedContent: String? = null,
)

/**
 * Сборка системного промпта из шаблона с плейсхолдерами:
 * `{locale} {time} {device} {memory} {skills} {screen_context} {shared_content}`.
 * Чистый рендер, JVM-тестируем. Капы с пометками — контекст не резиновый,
 * обрезка всегда явная, не молчаливая.
 */
object PromptRenderer {
    const val MAX_MEMORY_CHARS = 8000
    const val MAX_SKILL_BODY_CHARS = 4000
    const val MAX_SKILLS_TOTAL_CHARS = 12000

    fun render(template: String, inputs: PromptInputs): String {
        val russian = inputs.locale.startsWith("ru", ignoreCase = true)
        var out = template
        out = out.replace("{locale}", inputs.locale.trim())
        out = out.replace("{time}", inputs.time.trim())
        out = out.replace("{device}", inputs.device.trim())
        out = out.replace("{memory}", capMemory(inputs.memory.trim(), russian))
        out = out.replace("{skills}", skillsBlock(inputs.skills, russian))
        out = out.replace("{screen_context}", inputs.screenContext?.trim().orEmpty())
        out = out.replace("{shared_content}", inputs.sharedContent?.trim().orEmpty())
        return out
    }

    fun capMemory(memory: String, russian: Boolean = true): String {
        if (memory.length <= MAX_MEMORY_CHARS) return memory
        return memory.take(MAX_MEMORY_CHARS).trimEnd() + truncatedMark(russian)
    }

    fun skillsBlock(skills: List<SkillInfo>, russian: Boolean = true): String {
        val enabled = skills.filter { it.enabled }
        if (enabled.isEmpty()) return ""
        val sb = StringBuilder()
        var used = 0
        for (skill in enabled) {
            val body = if (skill.body.length > MAX_SKILL_BODY_CHARS) {
                skill.body.take(MAX_SKILL_BODY_CHARS).trimEnd() + truncatedMark(russian)
            } else {
                skill.body
            }
            if (used > 0 && used + body.length > MAX_SKILLS_TOTAL_CHARS) {
                sb.append(truncatedMark(russian)).append('\n')
                break
            }
            sb.append("## ").append(skill.name).append(" — ").append(skill.title).append('\n')
            sb.append(body).append("\n\n")
            used += body.length
        }
        return sb.toString().trim()
    }

    private fun truncatedMark(russian: Boolean): String =
        if (russian) "\n…[обрезано]" else "\n…[truncated]"
}
