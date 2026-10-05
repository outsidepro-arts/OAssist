package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ActionRisk

class ToolRegistryConfigTest {

    private class FakeTool(
        private val name: String,
        override val risk: ActionRisk = ActionRisk.AUTO,
    ) : Tool {
        var executions = 0
        override val schema = ToolSchema(
            name = name,
            description = "Тестовый инструмент",
            parameters = buildJsonObject { put("type", "object") },
        )
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult(true, "ok:$name")
        }
    }

    @Test
    fun schemas_excludesDisabledTools() {
        val registry = ToolRegistry(
            tools = listOf(FakeTool("a"), FakeTool("b")),
            disabled = setOf("b"),
        )
        assertEquals(listOf("a"), registry.schemas().map { it.name })
    }

    @Test
    fun execute_disabledToolReturnsExplicitError() = runTest {
        val tool = FakeTool("a")
        val registry = ToolRegistry(tools = listOf(tool), disabled = setOf("a"))
        val result = registry.execute("a", JsonObject(emptyMap()))
        assertFalse(result.success)
        assertTrue(result.output.contains("отключён"))
        assertEquals(0, tool.executions)
    }

    @Test
    fun effectiveRisk_appliesOverrides() {
        val registry = ToolRegistry(
            tools = listOf(FakeTool("auto_tool"), FakeTool("ask_tool", ActionRisk.ASK)),
            askOverrides = mapOf("auto_tool" to true, "ask_tool" to false),
        )
        // true поднимает AUTO до ASK, но не понижает ASK.
        assertEquals(ActionRisk.ASK, registry.effectiveRisk("auto_tool"))
        // false опускает всё до AUTO.
        assertEquals(ActionRisk.AUTO, registry.effectiveRisk("ask_tool"))
    }

    @Test
    fun effectiveRisk_defaultsAndUnknown() {
        val registry = ToolRegistry(
            tools = listOf(FakeTool("auto_tool"), FakeTool("ask_tool", ActionRisk.ASK)),
        )
        assertEquals(ActionRisk.AUTO, registry.effectiveRisk("auto_tool"))
        assertEquals(ActionRisk.ASK, registry.effectiveRisk("ask_tool"))
        // Неизвестный инструмент — fail-closed.
        assertEquals(ActionRisk.ASK, registry.effectiveRisk("no_such_tool"))
    }
}
