package ru.outsideproarts.oassist.core.model

/**
 * Нечёткий поиск по названиям чатов: токены запроса матчатся как подпоследовательность
 * текста («рцп» находит «Рецепты»), с бонусами за непрерывность, начало слова и
 * раннюю позицию. Pure — JVM-тестируем. Null-результат — токен не найден вовсе.
 */
object FuzzyMatcher {

    /** Оценка совпадения [query] с [text]; null — не найдено. Пустой запрос — 0 (подходит всё). */
    fun score(query: String, text: String): Int? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val t = text.lowercase()
        var total = 0
        for (token in q.split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            total += tokenScore(token, t) ?: return null
        }
        if (t.contains(q)) total += BONUS_FULL_PHRASE
        return total
    }

    /** Отфильтровать и отсортировать по убыванию совпадения; null score — мимо. */
    fun <T> rank(query: String, items: List<T>, title: (T) -> String): List<T> =
        items.mapNotNull { item -> score(query, title(item))?.let { it to item } }
            .sortedWith(compareByDescending<Pair<Int, T>> { it.first }.thenBy { title(it.second).length })
            .map { it.second }

    private fun tokenScore(token: String, text: String): Int? {
        var pos = 0
        var firstIndex = -1
        var run = 0
        var score = 0
        for (i in token.indices) {
            val found = text.indexOf(token[i], pos)
            if (found < 0) return null
            if (firstIndex < 0) firstIndex = found
            run = if (found == pos && i > 0) run + 1 else 0
            score += 1 + run
            if (found == 0 || !text[found - 1].isLetterOrDigit()) score += BONUS_WORD_START
            pos = found + 1
        }
        score += (BONUS_EARLY - firstIndex / EARLY_STEP).coerceAtLeast(0)
        if (text.contains(token)) score += BONUS_SUBSTRING
        return score
    }

    private const val BONUS_WORD_START = 3
    private const val BONUS_SUBSTRING = 15
    private const val BONUS_FULL_PHRASE = 20
    private const val BONUS_EARLY = 10
    private const val EARLY_STEP = 4
}
