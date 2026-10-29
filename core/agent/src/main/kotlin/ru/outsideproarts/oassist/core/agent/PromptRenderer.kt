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
    /** Лимит шагов инструментов за один ход; 0 — без лимита, null — не передан (пустая подстановка). */
    val stepsLimit: Int? = null,
)

data class RuntimePrompt(
    val instructions: String,
    val context: List<ContextData> = emptyList(),
)

/**
 * Сборка системного промпта из шаблона с плейсхолдерами:
 * `{locale} {time} {device} {memory} {skills} {screen_context} {shared_content} {steps_limit}`.
 * Чистый рендер, JVM-тестируем. Капы с пометками — контекст не резиновый,
 * обрезка всегда явная, не молчаливая.
 */
object PromptRenderer {
    const val MAX_MEMORY_CHARS = 8000

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
                "steps_limit" -> stepsLimitText(inputs.stepsLimit, russian)
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

    private val PLACEHOLDER =
        Regex("\\{(locale|time|device|memory|skills|screen_context|shared_content|steps_limit)\\}")

    /**
     * Значение `{steps_limit}`: число шагов либо явное «без ограничений».
     * Null (значение не передано) — пустая подстановка, как у остальных плейсхолдеров.
     */
    fun stepsLimitText(limit: Int?, russian: Boolean = true): String = when {
        limit == null -> ""
        limit <= 0 -> if (russian) "без ограничений" else "unlimited"
        else -> limit.toString()
    }

    fun capMemory(memory: String, russian: Boolean = true): String {
        if (memory.length <= MAX_MEMORY_CHARS) return memory
        return memory.take(MAX_MEMORY_CHARS).trimEnd() + truncatedMark(russian)
    }

    fun skillsBlock(skills: List<SkillInfo>, russian: Boolean = true): String {
        val enabled = skills.filter { it.enabled }
        if (enabled.isEmpty()) return ""
        val header = if (russian) {
            "Каталог навыков (имена и описания, не процедуры). Перед выполнением прочитай полный навык через skill_get."
        } else {
            "Skill catalog (names and descriptions, not procedures). Read the complete skill with skill_get before following it."
        }
        return enabled.joinToString("\n", prefix = "$header\n") { "## ${it.name} — ${it.title}" }
    }

    private fun truncatedMark(russian: Boolean): String =
        if (russian) "\n…[обрезано]" else "\n…[truncated]"
}
