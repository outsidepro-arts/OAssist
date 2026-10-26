package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.SkillInfo
import ru.outsideproarts.oassist.core.model.ContextData
import ru.outsideproarts.oassist.core.model.ContextSource

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

data class RuntimePrompt(
    val instructions: String,
    val context: List<ContextData> = emptyList(),
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

    fun render(template: String, inputs: PromptInputs): RuntimePrompt {
        val russian = inputs.locale.startsWith("ru", ignoreCase = true)
        val memory = capMemory(inputs.memory.trim(), russian)
        val screen = inputs.screenContext.orEmpty()
        val shared = inputs.sharedContent.orEmpty()
        val context = linkedMapOf<ContextSource, String>()
        val rendered = PLACEHOLDER.replace(template) { match ->
            when (match.groupValues[1]) {
                "locale" -> inputs.locale.trim()
                "time" -> inputs.time.trim()
                "device" -> inputs.device.trim()
                "skills" -> skillsBlock(inputs.skills, russian)
                "memory" -> dataReference(memory, ContextSource.MEMORY, context, russian)
                "screen_context" -> dataReference(screen, ContextSource.SCREEN, context, russian)
                "shared_content" -> dataReference(shared, ContextSource.SHARED_CONTENT, context, russian)
                else -> match.value
            }
        }
        return RuntimePrompt(
            instructions = rendered,
            context = context.map { (source, text) -> ContextData(source, text) },
        )
    }

    private fun dataReference(
        text: String,
        source: ContextSource,
        context: MutableMap<ContextSource, String>,
        russian: Boolean,
    ): String {
        if (text.isBlank()) return ""
        context.putIfAbsent(source, text)
        return when (source) {
            ContextSource.MEMORY -> if (russian) "[Данные из пользовательской памяти]" else "[User memory data]"
            ContextSource.SCREEN -> if (russian) "[Данные с экрана]" else "[Screen data]"
            ContextSource.SHARED_CONTENT -> if (russian) "[Переданные пользователем данные]" else "[User-shared data]"
            ContextSource.UNKNOWN_HISTORY -> if (russian) "[Исторические данные]" else "[Historical data]"
        }
    }

    private val PLACEHOLDER = Regex("\\{(locale|time|device|memory|skills|screen_context|shared_content)\\}")

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
