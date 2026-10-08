package ru.outsideproarts.oassist.core.agent

/**
 * Приложение с launcher-ярлыком: одна строка на пакет для модели.
 * [entries] — сколько launcher-activity у пакета (у некоторых приложений несколько ярлыков).
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val entries: Int = 1,
)

const val DEFAULT_APP_LIST_LIMIT = 100

private const val APP_LIST_MAX_CHARS = 10_000

/**
 * Список приложений для модели: сортировка по названию, фильтры, лимит и кап символов.
 * Приложения без launcher-activity сюда не попадают по построению (см. DeviceActions.listApps).
 */
fun formatAppList(
    apps: List<AppEntry>,
    query: String? = null,
    includeSystem: Boolean = true,
    limit: Int = DEFAULT_APP_LIST_LIMIT,
): String {
    val q = query?.trim().orEmpty()
    val filtered = apps.asSequence()
        .filter { includeSystem || !it.isSystem }
        .filter {
            q.isEmpty() ||
                it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()
    if (filtered.isEmpty()) {
        return if (q.isEmpty()) "Приложений не найдено" else "Ничего не найдено по запросу '$q'"
    }
    val shown = filtered.take(maxOf(limit, 1))
    val sb = StringBuilder()
    var count = 0
    for (app in shown) {
        val line = if (app.entries > 1) {
            "${app.label} (${app.packageName}) [+${app.entries} ярлыков]"
        } else {
            "${app.label} (${app.packageName})"
        }
        if (count > 0 && sb.length + line.length + 1 > APP_LIST_MAX_CHARS) break
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
        count++
    }
    if (count < filtered.size) {
        sb.append("\n…[обрезано: показано $count из ${filtered.size}]")
    }
    return sb.toString()
}
