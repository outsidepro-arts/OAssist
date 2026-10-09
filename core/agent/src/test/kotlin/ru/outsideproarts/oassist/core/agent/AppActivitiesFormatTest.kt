package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

class AppActivitiesFormatTest {

    private val activities = listOf(
        ActivityEntry("com.example.app.ZooActivity", "Zoo", exported = true),
        ActivityEntry("com.example.app.MapsActivity", "Maps", exported = true),
        ActivityEntry("com.example.app.HiddenActivity", "Hidden", exported = false),
        ActivityEntry("com.example.app.AlphaActivity", "Alpha", exported = true),
    )

    @Test
    fun format_exportedLine() {
        val out = formatActivityList(
            listOf(ActivityEntry("com.example.app.MainActivity", "Main", exported = true)),
        )
        assertEquals("com.example.app.MainActivity (Main)", out)
    }

    @Test
    fun format_nonExportedHiddenByDefault() {
        val out = formatActivityList(activities)
        assertEquals(
            "com.example.app.AlphaActivity (Alpha)\n" +
                "com.example.app.MapsActivity (Maps)\n" +
                "com.example.app.ZooActivity (Zoo)",
            out,
        )
    }

    @Test
    fun format_nonExportedMarkedWhenIncluded() {
        val out = formatActivityList(activities, includeNonExported = true)
        assertTrue("com.example.app.HiddenActivity (Hidden) — не экспортирована" in out)
    }

    @Test
    fun format_queryFiltersByLabelAndClass() {
        val byLabel = formatActivityList(activities, query = "alp")
        assertEquals("com.example.app.AlphaActivity (Alpha)", byLabel)

        val byClass = formatActivityList(activities, query = "MapsActivity")
        assertEquals("com.example.app.MapsActivity (Maps)", byClass)
    }

    @Test
    fun format_limitMarksTruncation() {
        val out = formatActivityList(activities, limit = 2)
        assertEquals(
            "com.example.app.AlphaActivity (Alpha)\n" +
                "com.example.app.MapsActivity (Maps)\n" +
                "…[обрезано: показано 2 из 3]",
            out,
        )
    }

    @Test
    fun format_emptyResults() {
        assertEquals("Активности не найдены", formatActivityList(emptyList()))
        assertEquals(
            "Ничего не найдено по запросу 'xyz'",
            formatActivityList(activities, query = "xyz"),
        )
    }

    @Test
    fun resolve_relativeAndFullNames() {
        assertEquals(
            "com.example.app.Settings",
            resolveActivityClassName("com.example.app", ".Settings"),
        )
        assertEquals(
            "com.example.app.Settings",
            resolveActivityClassName("com.example.app", "Settings"),
        )
        assertEquals(
            "com.other.Outer",
            resolveActivityClassName("com.example.app", "com.other.Outer"),
        )
    }

    @Test
    fun extras_typedValues() {
        val parsed = parseExtrasJson("""{"text": "привет", "count": 3, "flag": true, "ratio": 1.5}""")
        assertEquals(ExtraValue.Str("привет"), parsed["text"])
        assertEquals(ExtraValue.Long(3L), parsed["count"])
        assertEquals(ExtraValue.Bool(true), parsed["flag"])
        assertEquals(ExtraValue.Double(1.5), parsed["ratio"])
    }

    @Test
    fun extras_nestedStringifiedAndNullSkipped() {
        val parsed = parseExtrasJson("""{"obj": {"a": 1}, "arr": [1, 2], "gone": null}""")
        assertTrue((parsed["obj"] as ExtraValue.Str).value.contains("\"a\""))
        assertEquals(ExtraValue.Str("[1,2]"), parsed["arr"])
        assertTrue("gone" !in parsed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun extras_notJsonFails() {
        parseExtrasJson("not json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun extras_nonObjectFails() {
        parseExtrasJson("[1, 2]")
    }

    @Test
    fun groups_andCatalog() {
        assertEquals(
            ToolGroups.key(ToolCategory.APPS_NAVIGATION, ToolAccess.READ),
            ToolGroups.groupOf("app_activities"),
        )
        assertEquals(
            ToolGroups.key(ToolCategory.APPS_NAVIGATION, ToolAccess.WRITE),
            ToolGroups.groupOf("app_start_activity"),
        )
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue("app_activities" in names)
        assertTrue("app_start_activity" in names)
    }
}
