package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

class M52ToolsTest {

    @Test
    fun `percentToIndex отображает границы`() {
        assertEquals(0, percentToIndex(0, 15))
        assertEquals(15, percentToIndex(100, 15))
        assertEquals(7, percentToIndex(50, 15))
        assertEquals(0, percentToIndex(-10, 15))
        assertEquals(15, percentToIndex(150, 15))
    }

    @Test
    fun `percentToBrightness не даёт ноль`() {
        assertEquals(1, percentToBrightness(0))
        assertEquals(255, percentToBrightness(100))
        assertEquals(127, percentToBrightness(50))
    }

    @Test
    fun `тулы M5_2 входят в группы записи и спрашиваются`() {
        val descriptors = ToolCatalog.descriptors().associate { it.name to it.groupKey }
        assertEquals(ToolGroups.key(ToolCategory.COMMUNICATION, ToolAccess.WRITE), descriptors["notify_reply"])
        assertEquals(ToolGroups.key(ToolCategory.DEVICE, ToolAccess.WRITE), descriptors["volume_set"])
        assertEquals(ToolGroups.key(ToolCategory.DEVICE, ToolAccess.WRITE), descriptors["dnd_set"])
        assertEquals(ToolGroups.key(ToolCategory.DEVICE, ToolAccess.WRITE), descriptors["brightness_set"])
    }

    @Test
    fun `notify_reply при пустом listener честно падает fail-closed`() {
        val tool = NotifyReplyTool(NotifyActions { null })
        assertEquals(ToolGroups.key(ToolCategory.COMMUNICATION, ToolAccess.WRITE), tool.groupKey)
        val args = buildJsonObject {
            put("key", "k")
            put("text", "привет")
        }
        val result = runBlocking { tool.execute(args) }
        assertFalse(result.success)
        assertTrue(result.output.contains("уведомлениям"))
    }

    @Test
    fun `notify_reply валидирует аргументы`() {
        val tool = NotifyReplyTool(NotifyActions { null })
        val noKey = runBlocking { tool.execute(buildJsonObject { put("text", "x") }) }
        assertFalse(noKey.success)
        val noText = runBlocking { tool.execute(buildJsonObject { put("key", "k") }) }
        assertFalse(noText.success)
    }

    @Test
    fun `reply подтверждает текст`() {
        val tool = NotifyReplyTool(NotifyActions { null })
        val args = buildJsonObject {
            put("key", "k")
            put("text", "созвон переносим")
        }
        assertTrue(tool.describeForConfirmation(args).contains("созвон переносим"))
    }
}
