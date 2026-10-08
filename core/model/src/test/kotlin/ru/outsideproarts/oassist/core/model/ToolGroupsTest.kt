package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGroupsTest {

    @Test
    fun `все известные тулы имеют категорийную группу`() {
        ToolGroups.knownToolNames.forEach { name ->
            val group = ToolGroups.groupOf(name)
            assertFalse("тул $name без группы", group == ToolGroups.UNKNOWN)
        }
    }

    @Test
    fun `bye только в служебной группе`() {
        assertEquals(ToolGroups.SERVICE, ToolGroups.groupOf("bye"))
        assertEquals(
            listOf("bye"),
            ToolGroups.knownToolNames.filter { ToolGroups.groupOf(it) == ToolGroups.SERVICE },
        )
    }

    @Test
    fun `группы чтения и записи разделены`() {
        assertEquals(
            ToolGroups.key(ToolCategory.PHONE_DATA, ToolAccess.READ),
            ToolGroups.groupOf("contact_find"),
        )
        assertEquals(
            ToolGroups.key(ToolCategory.PHONE_DATA, ToolAccess.WRITE),
            ToolGroups.groupOf("calendar_add"),
        )
        // Память — отдельная категория, не данные телефона.
        assertEquals(
            ToolGroups.key(ToolCategory.APP_DATA, ToolAccess.WRITE),
            ToolGroups.groupOf("memory_put"),
        )
    }

    @Test
    fun `categoryGroups не содержит служебных`() {
        assertTrue(ToolGroups.SERVICE !in ToolGroups.categoryGroups())
        assertTrue(ToolGroups.categoryGroups().size >= 15)
    }

    @Test
    fun `mcp ключи и тип доступа`() {
        val key = ToolGroups.mcp("srv-1")
        assertTrue(ToolGroups.isMcp(key))
        assertEquals("srv-1", ToolGroups.mcpServerId(key))
        assertNull(ToolGroups.accessOf(key))
        assertNull(ToolGroups.accessOf(ToolGroups.SERVICE))
        assertEquals(ToolAccess.READ, ToolGroups.accessOf(ToolGroups.key(ToolCategory.WEB, ToolAccess.READ)))
    }

    @Test
    fun `неизвестный тул — fail-closed`() {
        assertEquals(ToolGroups.UNKNOWN, ToolGroups.groupOf("no_such_tool"))
    }
}
