package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Activity приложения: кандидат на явный запуск через [DeviceActions.startAppActivity].
 *
 * Платформа не отдаёт intent-filter'ы пакета (`GET_INTENT_FILTERS` deprecated —
 * «platform does not support getting IntentFilters»), поэтому перечисляем
 * экспортированные activity как запускаемые цели, а не «все интенты».
 */
data class ActivityEntry(
    val className: String,
    val label: String,
    val exported: Boolean,
    val enabled: Boolean = true,
)

const val DEFAULT_APP_ACTIVITIES_LIMIT = 100

private const val APP_ACTIVITIES_MAX_CHARS = 10_000

/**
 * Список activity для модели: фильтр по подстроке, признак экспорта, лимит и кап символов.
 * Отключённые (`enabled=false`) сюда не попадают по построению (см. `DeviceActions`).
 */
fun formatActivityList(
    activities: List<ActivityEntry>,
    query: String? = null,
    includeNonExported: Boolean = false,
    limit: Int = DEFAULT_APP_ACTIVITIES_LIMIT,
): String {
    val q = query?.trim().orEmpty()
    val filtered = activities.asSequence()
        .filter { includeNonExported || it.exported }
        .filter {
            q.isEmpty() ||
                it.label.contains(q, ignoreCase = true) ||
                it.className.contains(q, ignoreCase = true)
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.className })
        .toList()
    if (filtered.isEmpty()) {
        return if (q.isEmpty()) "Активности не найдены" else "Ничего не найдено по запросу '$q'"
    }
    val shown = filtered.take(maxOf(limit, 1))
    val sb = StringBuilder()
    var count = 0
    for (entry in shown) {
        val line = if (entry.exported) {
            "${entry.className} (${entry.label})"
        } else {
            "${entry.className} (${entry.label}) — не экспортирована"
        }
        if (count > 0 && sb.length + line.length + 1 > APP_ACTIVITIES_MAX_CHARS) break
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
        count++
    }
    if (count < filtered.size) {
        sb.append("\n…[обрезано: показано $count из ${filtered.size}]")
    }
    return sb.toString()
}

/**
 * Имя класса activity в полное: манифест допускает относительные имена.
 * `.Settings` → `pkg.Settings`; `Settings` → `pkg.Settings`; полное — как есть.
 * Чистая — покрыта тестом.
 */
fun resolveActivityClassName(packageName: String, activity: String): String {
    val name = activity.trim()
    return when {
        name.startsWith(".") -> packageName + name
        "." in name -> name
        else -> "$packageName.$name"
    }
}

/** Типизированное значение extra для `Intent.putExtra`. */
sealed interface ExtraValue {
    data class Str(val value: String) : ExtraValue
    data class Bool(val value: Boolean) : ExtraValue
    data class Long(val value: kotlin.Long) : ExtraValue
    data class Double(val value: kotlin.Double) : ExtraValue
}

/**
 * Разбор `extras_json` в типизированные значения.
 * Примитивы маппятся по JSON-типу (bool → bool, целое → long, дробное → double,
 * строка → string); вложенные объекты/массивы — строкой JSON (модели текст).
 * `null` пропускаются. Не объект / не JSON — `IllegalArgumentException` с русской причиной.
 * Чистая — покрыта тестом.
 */
fun parseExtrasJson(extrasJson: String): Map<String, ExtraValue> {
    val element = runCatching { Json.parseToJsonElement(extrasJson) }
        .getOrElse { throw IllegalArgumentException("extras_json — не JSON: ${it.message}") }
    if (element !is JsonObject) {
        throw IllegalArgumentException("extras_json должен быть JSON-объектом, например {\"text\": \"привет\"}")
    }
    val result = LinkedHashMap<String, ExtraValue>()
    for ((key, value) in element) {
        when (value) {
            is JsonNull -> Unit // null пропускаем молча
            is JsonPrimitive -> {
                val parsed = when {
                    value.isString -> ExtraValue.Str(value.content)
                    value.booleanOrNull != null -> ExtraValue.Bool(value.booleanOrNull == true)
                    value.longOrNull != null -> ExtraValue.Long(value.longOrNull ?: 0L)
                    value.doubleOrNull != null -> ExtraValue.Double(value.doubleOrNull ?: 0.0)
                    else -> ExtraValue.Str(value.content)
                }
                result[key] = parsed
            }
            is JsonObject, is JsonArray -> result[key] = ExtraValue.Str(value.toString())
        }
    }
    return result
}
