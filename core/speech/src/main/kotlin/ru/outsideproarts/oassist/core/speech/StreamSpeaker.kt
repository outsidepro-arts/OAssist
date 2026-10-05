package ru.outsideproarts.oassist.core.speech

/**
 * По-предложенческая озвучка нарастающего текста ответа.
 *
 * Один класс на чат/ассист/шаринг вместо трёх копий счётчика `spoken`.
 * Правила (см. [ru.outsideproarts.oassist.core.model.TtsConfig]):
 * - готовые предложения говорятся сразу, недописанное — ждёт;
 * - комментарий к вызову тула (`isToolComment` от AgentLoop) — только при
 *   `speakToolCalls`, иначе молча продвигаем счётчик (коммент не должен
 *   вылезти в хвосте на Finish);
 * - служебные строки `[tool]` / `[tool: result]` не говорятся никогда —
 *   тип инструмента не зачитывается;
 * - хвост без точки договаривается на [onFinish] (ответ закончен).
 *
 * Чистый Kotlin (JVM-тестируем): говорит через колбэк [speak].
 */
class StreamSpeaker(
    private val speak: (String) -> Unit,
    private val speakToolCalls: Boolean = true,
    private val maxCommentChars: Int = 500,
) {
    private var spoken = 0

    fun reset() {
        spoken = 0
    }

    /** Новый полный текст ответа; [isToolComment] — флаг чанка от AgentLoop. */
    fun onText(full: String, isToolComment: Boolean) {
        val complete = SentenceSplitter.completePrefix(full)
        if (complete.length <= spoken) return
        val fresh = complete.substring(spoken)
        spoken = complete.length
        if (isToolComment && !speakToolCalls) return
        var text = SentenceSplitter.stripServiceLines(fresh)
        if (isToolComment) text = capComment(text)
        SentenceSplitter.splitSentences(text).forEach(speak)
    }

    /** Ответ закончен: договорить остаток (служебные строки вырезаны). */
    fun onFinish(full: String) {
        if (full.length <= spoken) return
        val tail = full.substring(spoken)
        spoken = full.length
        SentenceSplitter.splitSentences(SentenceSplitter.stripServiceLines(tail)).forEach(speak)
    }

    /**
     * Комментарий — первые предложения в бюджет [maxCommentChars] (длинные
     * рассуждения модели целиком не зачитываем), минимум первое предложение.
     */
    private fun capComment(text: String): String {
        val sentences = SentenceSplitter.splitSentences(text)
        if (sentences.isEmpty()) return ""
        val kept = mutableListOf<String>()
        var used = 0
        for (s in sentences) {
            if (kept.isNotEmpty() && used + s.length > maxCommentChars) break
            kept.add(s)
            used += s.length
        }
        return kept.joinToString(" ")
    }
}
