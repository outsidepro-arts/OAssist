package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiObservationRegistryTest {
    private val node = UiNode(0, "Button", "Send", null, true, false, false, UiRect(1, 2, 3, 4))
    private val fingerprint = UiNodeFingerprint(node, 7, "com.example", "id/send", true, false, true)

    @Test
    fun `snapshot token identity and properties must all match`() {
        val disposed = mutableListOf<String>()
        val registry = UiObservationRegistry<String> { disposed.add(it) }
        registry.publish("snapshot-a", listOf(UiObservedNode("node-a", fingerprint)))
        assertTrue(registry.matches("snapshot-a", 0, "node-a", fingerprint))
        assertFalse(registry.matches(null, 0, "node-a", fingerprint))
        assertFalse(registry.matches("snapshot-b", 0, "node-a", fingerprint))
        assertFalse(registry.matches("snapshot-a", 0, "node-b", fingerprint))
        assertFalse(registry.matches("snapshot-a", 0, "node-a", fingerprint.copy(windowId = 8)))
        assertFalse(registry.matches("snapshot-a", 0, "node-a", fingerprint.copy(node = node.copy(text = "Delete"))))
        assertFalse(registry.matches("snapshot-a", 0, "node-a", fingerprint.copy(node = node.copy(bounds = UiRect(2, 2, 3, 4)))))
        registry.publish("snapshot-b", listOf(UiObservedNode("node-b", fingerprint)))
        assertEquals(listOf("node-a"), disposed)
        assertFalse(registry.matches("snapshot-a", 0, "node-b", fingerprint))
        assertTrue(registry.matches("snapshot-b", 0, "node-b", fingerprint))
        registry.clear()
        assertEquals(listOf("node-a", "node-b"), disposed)
        assertNull(registry.expected("snapshot-b", 0))
    }

    @Test
    fun `action index only resolves against the previously advertised identical list`() {
        val registry = UiObservationRegistry<String> { }
        registry.publish("snapshot", listOf(UiObservedNode("node", fingerprint)))
        val actions = listOf(UiNodeAction(0, 42, "Reply"), UiNodeAction(1, 43, "Delete"))
        assertNull(registry.action("snapshot", 0, 0, actions))
        assertTrue(registry.advertise("snapshot", 0, actions))
        assertEquals(actions[0], registry.action("snapshot", 0, 0, actions))
        val changed = listOf(actions[1].copy(index = 0), actions[0].copy(index = 1))
        assertFalse(registry.advertise("snapshot", 0, changed))
        assertNull(registry.action("snapshot", 0, 0, changed))
        registry.publish("next", listOf(UiObservedNode("node", fingerprint)))
        assertNull(registry.action("next", 0, 0, actions))
    }

    @Test
    fun `all indexed tools require snapshot id and scroll enforces it only with index`() = runBlocking {
        val ui = UiActions(serviceProvider = { null })
        val tools = listOf(UiClickTool(ui), UiLongClickTool(ui), UiSetTextTool(ui),
            UiGetActionsTool(ui), UiPerformActionTool(ui), UiScreenshotNodeTool(ui))
        val args = buildJsonObject { put("index", 0); put("action_index", 0); put("text", "x") }
        tools.forEach { tool ->
            assertTrue(tool.schema.parameters["required"]!!.jsonArray.any { it.jsonPrimitive.content == "snapshot_id" })
            val result = tool.execute(args)
            assertFalse(result.success)
            assertTrue(result.output.contains("snapshot_id"))
        }
        val scroll = UiScrollTool(ui)
        val indexed = scroll.execute(buildJsonObject { put("index", 0); put("direction", "down") })
        assertFalse(indexed.success)
        assertTrue(indexed.output.contains("snapshot_id"))
        val automatic = scroll.execute(buildJsonObject { put("direction", "down") })
        assertNotEquals(indexed.output, automatic.output)
    }
}
