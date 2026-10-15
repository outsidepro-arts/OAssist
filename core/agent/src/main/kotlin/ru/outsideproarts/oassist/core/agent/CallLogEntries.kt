package ru.outsideproarts.oassist.core.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, OTHER }

enum class CallPresentation { NORMAL, UNKNOWN, RESTRICTED }

data class CallLogEntry(
    val number: String?,
    val name: String?,
    val dateMillis: Long,
    val durationSec: Int,
    val type: CallType,
    val presentation: CallPresentation = CallPresentation.NORMAL,
)

const val DEFAULT_CALL_LOG_LIMIT = 20

const val CALL_LOG_FETCH_CAP = 500

private const val CALL_LOG_MAX_CHARS = 10_000

/** Значения CallLog.Calls.TYPE: 1=INCOMING, 2=OUTGOING, 3=MISSED, 5=REJECTED, остальное — прочее. */
fun callTypeFromInt(raw: Int): CallType = when (raw) {
    1 -> CallType.INCOMING
    2 -> CallType.OUTGOING
    3 -> CallType.MISSED
    5 -> CallType.REJECTED
    else -> CallType.OTHER
}

/** Значения CallLog.Calls.NUMBER_PRESENTATION: 2=RESTRICTED, 3=UNKNOWN, 4=PAYPHONE. */
fun callPresentationFromInt(raw: Int): CallPresentation = when (raw) {
    2 -> CallPresentation.RESTRICTED
    3, 4 -> CallPresentation.UNKNOWN
    else -> CallPresentation.NORMAL
}

/** Фильтр по типу из параметра тула; null/`all` — без фильтра, неизвестное значение — ошибка. */
fun callTypeFilterOf(raw: String?): CallType? = when (val v = raw?.trim()?.lowercase()) {
    null, "", "all" -> null
    "incoming" -> CallType.INCOMING
    "outgoing" -> CallType.OUTGOING
    "missed" -> CallType.MISSED
    "rejected" -> CallType.REJECTED
    else -> throw IllegalArgumentException(
        "Неизвестный тип звонка '$v': допустимы all, incoming, outgoing, missed, rejected",
    )
}

fun callTypeLabel(type: CallType): String = when (type) {
    CallType.INCOMING -> "Входящий"
    CallType.OUTGOING -> "Исходящий"
    CallType.MISSED -> "Пропущенный"
    CallType.REJECTED -> "Отклонённый"
    CallType.OTHER -> "Другой"
}

fun formatCallDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec) else "%d:%02d".format(Locale.US, m, sec)
}

fun formatCallDate(dateMillis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(dateMillis))

/**
 * Журнал вызовов для модели: сортировка от новых к старым, фильтры, лимит и кап символов.
 * Порядок входного списка не важен.
 */
fun formatCallLog(
    entries: List<CallLogEntry>,
    query: String? = null,
    type: CallType? = null,
    limit: Int = DEFAULT_CALL_LOG_LIMIT,
): String {
    val q = query?.trim().orEmpty()
    val filtered = entries.asSequence()
        .filter { type == null || it.type == type }
        .filter {
            q.isEmpty() ||
                it.number?.contains(q, ignoreCase = true) == true ||
                it.name?.contains(q, ignoreCase = true) == true
        }
        .sortedByDescending { it.dateMillis }
        .toList()
    if (filtered.isEmpty()) {
        return if (q.isEmpty()) {
            "Журнал вызовов пуст"
        } else {
            "Ничего не найдено по запросу '$q'"
        }
    }
    val shown = filtered.take(maxOf(limit, 1))
    val sb = StringBuilder()
    var count = 0
    for (entry in shown) {
        val line = formatCallLine(entry)
        if (count > 0 && sb.length + line.length + 1 > CALL_LOG_MAX_CHARS) break
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
        count++
    }
    if (count < filtered.size) {
        sb.append("\n…[обрезано: показано $count из ${filtered.size}]")
    }
    return sb.toString()
}

internal fun formatCallLine(entry: CallLogEntry): String {
    val subject = when {
        entry.presentation == CallPresentation.RESTRICTED -> "номер скрыт"
        entry.presentation == CallPresentation.UNKNOWN -> "неизвестный номер"
        !entry.name.isNullOrBlank() && !entry.number.isNullOrBlank() -> "${entry.name} (${entry.number})"
        !entry.name.isNullOrBlank() -> entry.name!!
        !entry.number.isNullOrBlank() -> entry.number!!
        else -> "неизвестный номер"
    }
    val duration = if (entry.durationSec > 0) " — ${formatCallDuration(entry.durationSec)}" else ""
    return "[${formatCallDate(entry.dateMillis)}] ${callTypeLabel(entry.type)} — $subject$duration"
}
