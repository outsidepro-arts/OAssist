package ru.outsideproarts.oassist.core.speech

/**
 * Разбиение текста на предложения для потоковой озвучки.
 *
 * Чистая (JVM-тестируемая) логика: гранулярность — по предложениям, как у TTS.
 * Стрим нарастает дельтами, поэтому [completePrefix] отдаёт только завершённый
 * префикс — недописанное предложение договаривать нельзя, оно ещё может измениться.
 */
object SentenceSplitter {

    /**
     * Граница предложения: знак конца + пробельный разделитель.
     * Сокращения вида «т.е.» режутся — осознанно: уровень гранулярности TTS,
     * а не лингвистическая точность.
     */
    private val BOUNDARY = Regex("(?<=[.!?…])\\s+")

    /** Разбить текст на предложения, пустые отбросить. */
    fun splitSentences(text: String): List<String> =
        text.split(BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Префикс [text], состоящий только из завершённых предложений.
     * Хвост, оканчивающийся знаком конца предложения, тоже готов —
     * пробел после точки не обязателен (AgentLoop отдаёт текст целым).
     * Нет готового — пустая строка (говорить пока нечего).
     */
    fun completePrefix(text: String): String {
        if (text.isBlank()) return ""
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && trimmed.last() in ".!?…") return trimmed
        val last = BOUNDARY.findAll(text).lastOrNull() ?: return ""
        return text.substring(0, last.range.first).trim()
    }

    /**
     * Служебные строки цикла (`[tool]`, `[tool: result]`): видимый текст,
     * который нельзя озвучивать — тип инструмента не зачитывается никогда.
     * Имя — слово/дефис (имена тулов без пробелов), поэтому обычная проза
     * в скобках под фильтр не попадает.
     */
    private val SERVICE_LINE = Regex("^\\[[\\w\\-]+\\s*(?::.*)?\\]$")

    /** Убрать служебные строки, остальное (включая многострочные выводы) оставить. */
    fun stripServiceLines(text: String): String =
        text.lines().filterNot { SERVICE_LINE.matches(it.trim()) }.joinToString("\n")
}
