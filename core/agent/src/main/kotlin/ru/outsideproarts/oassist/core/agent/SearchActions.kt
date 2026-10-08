package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.model.SearchEngine

/** Источник настройки поисковой системы (фейки в тестах). */
fun interface SearchConfigSource {
    suspend fun engine(): SearchEngine
}

/** Browser-like UA: часть движков без него отдаёт капчу или пустую выдачу. */
private const val SEARCH_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

const val SEARCH_MAX_RESULTS = 8
private const val SEARCH_MAX_CHARS = 4_000

/**
 * Настоящий поиск: HTML-выдача настроенной поисковой системы → top-N в контекст модели.
 * Без фолбэков между движками (решение пользователя): отказ выбранного движка —
 * честная ошибка с подсказкой сменить его в настройках.
 */
class SearchActions(
    private val webContent: WebContentActions,
    private val config: SearchConfigSource,
) {

    suspend fun search(query: String): ToolResult {
        if (query.isBlank()) {
            return ToolResult(false, "Не указан query — передайте поисковый запрос в параметре query")
        }
        val engine = config.engine()
        val fetch = webContent.fetchStatic(buildSearchUrl(engine, query), userAgent = SEARCH_USER_AGENT)
        val html = fetch.getOrElse { return ToolResult(false, mapSearchError(engine, it)) }
        return interpretSearch(engine, query, html.body)
    }
}

/** Чистая интерпретация выдачи — тестируется на JVM без сети. */
internal fun interpretSearch(engine: SearchEngine, query: String, html: String): ToolResult {
    val parsed = SearchParsers.parse(engine, html)
    if (parsed.blocked) {
        return ToolResult(
            false,
            "Поисковая система «${engineLabel(engine)}» заблокировала автоматический запрос (капча/антибот) " +
                "— смените поисковую систему в настройках",
        )
    }
    if (parsed.results.isEmpty()) {
        return ToolResult(
            false,
            "Поисковая система «${engineLabel(engine)}» не вернула результатов по запросу «$query» " +
                "— возможно, изменилась разметка выдачи или запрос слишком узкий",
        )
    }
    return ToolResult(true, formatSearchResults(engine, query, parsed.results))
}

internal fun formatSearchResults(engine: SearchEngine, query: String, results: List<SearchResult>): String {
    val sb = StringBuilder()
    sb.appendLine("Результаты поиска «${engineLabel(engine)}» по запросу «$query»:")
    results.take(SEARCH_MAX_RESULTS).forEachIndexed { i, r ->
        sb.appendLine("${i + 1}. ${r.title}")
        sb.appendLine("   ${r.url}")
        if (r.snippet.isNotBlank()) sb.appendLine("   ${r.snippet.take(200)}")
    }
    sb.append(
        "(показано ${minOf(SEARCH_MAX_RESULTS, results.size)} из ${results.size}; " +
            "содержимое страницы несётся в модель через web_fetch)",
    )
    return sb.toString().take(SEARCH_MAX_CHARS)
}

internal fun mapSearchError(engine: SearchEngine, error: Throwable): String =
    "Не удалось выполнить поиск через «${engineLabel(engine)}»: ${mapFetchError(error)}"