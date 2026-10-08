package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

class AppListFormatTest {

    private val apps = listOf(
        AppEntry("com.example.zoo", "Zoo", isSystem = false),
        AppEntry("com.example.maps", "Maps", isSystem = true),
        AppEntry("com.example.alpha", "Alpha", isSystem = false),
    )

    @Test
    fun format_lineWithPackage() {
        val out = formatAppList(listOf(AppEntry("com.example.app", "My App", isSystem = false)))
        assertEquals("My App (com.example.app)", out)
    }

    @Test
    fun format_multiEntryMarked() {
        val out = formatAppList(
            listOf(AppEntry("com.example.app", "My App", isSystem = false, entries = 3)),
        )
        assertEquals("My App (com.example.app) [+3 ярлыков]", out)
    }

    @Test
    fun format_sortsByLabelIgnoringCase() {
        val out = formatAppList(apps)
        assertEquals(
            "Alpha (com.example.alpha)\nMaps (com.example.maps)\nZoo (com.example.zoo)",
            out,
        )
    }

    @Test
    fun format_queryFiltersByLabelAndPackage() {
        val byLabel = formatAppList(apps, query = "zo")
        assertEquals("Zoo (com.example.zoo)", byLabel)

        val byPackage = formatAppList(apps, query = "example.map")
        assertEquals("Maps (com.example.maps)", byPackage)

        val caseInsensitive = formatAppList(apps, query = "ALPHA")
        assertEquals("Alpha (com.example.alpha)", caseInsensitive)
    }

    @Test
    fun format_excludesSystemApps() {
        val out = formatAppList(apps, includeSystem = false)
        assertEquals(
            "Alpha (com.example.alpha)\nZoo (com.example.zoo)",
            out,
        )
    }

    @Test
    fun format_limitMarksTruncation() {
        val out = formatAppList(apps, limit = 2)
        assertEquals(
            "Alpha (com.example.alpha)\nMaps (com.example.maps)\n…[обрезано: показано 2 из 3]",
            out,
        )
    }

    @Test
    fun format_emptyResults() {
        assertEquals("Приложений не найдено", formatAppList(emptyList()))
        assertEquals("Ничего не найдено по запросу 'xyz'", formatAppList(apps, query = "xyz"))
    }

    @Test
    fun group_isAppsNavigationRead() {
        assertEquals(
            ToolGroups.key(ToolCategory.APPS_NAVIGATION, ToolAccess.READ),
            ToolGroups.groupOf("app_list"),
        )
        assertTrue("app_list" in ToolCatalog.descriptors().map { it.name })
    }
}
