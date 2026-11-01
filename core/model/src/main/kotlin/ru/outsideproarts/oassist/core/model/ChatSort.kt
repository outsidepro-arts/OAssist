package ru.outsideproarts.oassist.core.model

/**
 * Порядок сортировки списка чатов (шторка/шаринг). Закреплённая запись
 * ассистента (ChatKind.ASSISTANT) сортировке не подлежит — она рендерится
 * отдельно и в отсортированный список не входит.
 */
enum class ChatSortOrder {
    /** Новые сверху (по updatedAt). */
    DATE_DESC,

    /** Старые сверху (по updatedAt). */
    DATE_ASC,

    /** По названию, регистронезависимо. */
    TITLE,
    ;

    companion object {
        /** Разбор сохранённого имени; мусор и null — дефолт [DATE_DESC]. */
        fun fromName(raw: String?): ChatSortOrder =
            entries.firstOrNull { it.name == raw } ?: DATE_DESC
    }
}

/**
 * Отсортировать чаты по [order]. Стабильная сортировка: равные ключи
 * сохраняют исходный порядок списка (из БД — updatedAt по убыванию).
 * Дата — [ChatSummary.updatedAt] (та, что показывается в подзаголовке строки).
 */
fun List<ChatSummary>.sortedByOrder(order: ChatSortOrder): List<ChatSummary> = when (order) {
    ChatSortOrder.DATE_DESC -> sortedByDescending { it.updatedAt }
    ChatSortOrder.DATE_ASC -> sortedBy { it.updatedAt }
    ChatSortOrder.TITLE -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}
