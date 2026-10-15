package ru.outsideproarts.oassist.core.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

class CallLogFormatTest {

    private fun date(ts: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))

    private val day = 1_728_000_000_000L

    private val entries = listOf(
        CallLogEntry(
            number = "+79001234567",
            name = "Иван",
            dateMillis = day + 3_600_000,
            durationSec = 225,
            type = CallType.OUTGOING,
        ),
        CallLogEntry(
            number = "+79001234567",
            name = null,
            dateMillis = day + 7_200_000,
            durationSec = 0,
            type = CallType.MISSED,
        ),
        CallLogEntry(
            number = null,
            name = null,
            dateMillis = day,
            durationSec = 10,
            type = CallType.INCOMING,
            presentation = CallPresentation.RESTRICTED,
        ),
    )

    @Test
    fun format_lineWithTypeDuration() {
        val out = formatCallLog(listOf(entries[0]))
        assertEquals(
            "[${date(day + 3_600_000)}] Исходящий — Иван (+79001234567) — 3:45",
            out,
        )
    }

    @Test
    fun format_missedWithoutDuration() {
        val out = formatCallLog(listOf(entries[1]))
        assertEquals("[${date(day + 7_200_000)}] Пропущенный — +79001234567", out)
    }

    @Test
    fun format_restrictedAndUnknownNumbers() {
        val restricted = formatCallLog(listOf(entries[2]))
        assertEquals("[${date(day)}] Входящий — номер скрыт — 0:10", restricted)

        val unknown = formatCallLog(
            listOf(entries[2].copy(presentation = CallPresentation.UNKNOWN, durationSec = 0)),
        )
        assertEquals("[${date(day)}] Входящий — неизвестный номер", unknown)
    }

    @Test
    fun format_nameOnlyAndNumberOnly() {
        val nameOnly = formatCallLog(listOf(entries[0].copy(number = null)))
        assertTrue(nameOnly.contains("Иван —"))
        assertTrue(!nameOnly.contains("("))

        val numberOnly = formatCallLog(listOf(entries[0].copy(name = null)))
        assertTrue(numberOnly.contains("— +79001234567 —"))
    }

    @Test
    fun format_sortsNewestFirst() {
        val out = formatCallLog(entries)
        val lines = out.split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("Пропущенный"))
        assertTrue(lines[1].contains("Исходящий"))
        assertTrue(lines[2].contains("Входящий"))
    }

    @Test
    fun format_queryFiltersByNameAndNumber() {
        val byName = formatCallLog(entries, query = "иван")
        assertTrue(byName.contains("Исходящий"))
        assertTrue(!byName.contains("Пропущенный"))

        val byNumber = formatCallLog(entries, query = "9001234567")
        assertEquals(2, byNumber.split('\n').size)

        val byRestricted = formatCallLog(entries, query = "скрыт")
        assertTrue(byRestricted.startsWith("Ничего не найдено"))
    }

    @Test
    fun format_typeFilter() {
        val missed = formatCallLog(entries, type = CallType.MISSED)
        assertEquals(1, missed.split('\n').size)
        assertTrue(missed.contains("Пропущенный"))

        val outgoing = formatCallLog(entries, type = CallType.OUTGOING)
        assertTrue(outgoing.contains("Исходящий"))
    }

    @Test
    fun format_limitMarksTruncation() {
        val out = formatCallLog(entries, limit = 2)
        val lines = out.split('\n')
        assertEquals(3, lines.size)
        assertEquals("…[обрезано: показано 2 из 3]", lines[2])
    }

    @Test
    fun format_emptyResults() {
        assertEquals("Журнал вызовов пуст", formatCallLog(emptyList()))
        assertEquals(
            "Ничего не найдено по запросу 'xyz'",
            formatCallLog(entries, query = "xyz"),
        )
    }

    @Test
    fun duration_formatsMinutesAndHours() {
        assertEquals("0:45", formatCallDuration(45))
        assertEquals("3:45", formatCallDuration(225))
        assertEquals("1:02:05", formatCallDuration(3725))
        assertEquals("0:00", formatCallDuration(-5))
    }

    @Test
    fun rawProviderIntsMapToTypes() {
        assertEquals(CallType.INCOMING, callTypeFromInt(1))
        assertEquals(CallType.OUTGOING, callTypeFromInt(2))
        assertEquals(CallType.MISSED, callTypeFromInt(3))
        assertEquals(CallType.REJECTED, callTypeFromInt(5))
        assertEquals(CallType.OTHER, callTypeFromInt(4))
        assertEquals(CallType.OTHER, callTypeFromInt(0))

        assertEquals(CallPresentation.NORMAL, callPresentationFromInt(1))
        assertEquals(CallPresentation.RESTRICTED, callPresentationFromInt(2))
        assertEquals(CallPresentation.UNKNOWN, callPresentationFromInt(3))
        assertEquals(CallPresentation.UNKNOWN, callPresentationFromInt(4))
    }

    @Test
    fun typeFilterParsesToolParam() {
        assertNull(callTypeFilterOf(null))
        assertNull(callTypeFilterOf(""))
        assertNull(callTypeFilterOf("all"))
        assertEquals(CallType.MISSED, callTypeFilterOf("missed"))
        assertEquals(CallType.REJECTED, callTypeFilterOf(" REJECTED "))
        try {
            callTypeFilterOf("spam")
            throw AssertionError("ожидался IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun group_isCallLogRead() {
        assertEquals(
            ToolGroups.key(ToolCategory.CALL_LOG, ToolAccess.READ),
            ToolGroups.groupOf("call_log_list"),
        )
        assertTrue("call_log_list" in ToolCatalog.descriptors().map { it.name })
    }
}
