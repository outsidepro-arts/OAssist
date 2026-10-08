package ru.outsideproarts.oassist.core.model

/**
 * Поисковая система для тула `web_search`. Никаких ключей: HTML-выдача + свой парсер.
 * Часть движков (Google/Яндекс/Mail.ru) агрессивно блокирует автоматические запросы —
 * при отказе тул честно сообщает об этом, без фолбэков (решение пользователя).
 */
enum class SearchEngine(val id: String) {
    /** Дефолт: без ключей, стабильная выдача, нормальные русскоязычные результаты. */
    DUCKDUCKGO("duckduckgo"),
    YANDEX("yandex"),
    GOOGLE("google"),
    BING("bing"),
    MAILRU("mailru"),
    ECOSIA("ecosia"),
    BRAVE("brave"),
    MOJEEK("mojeek"),
    ;

    companion object {
        val DEFAULT: SearchEngine = DUCKDUCKGO

        /** Неизвестный/легаси id — дефолт (не падаем на старых настройках). */
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}