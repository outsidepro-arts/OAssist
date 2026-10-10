package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolGroups

class ToolRegistryConfigTest {

    private class FakeTool(
        private val name: String,
        override val groupKey: String = ToolGroups.groupOf(name),
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
    fun groupKey_resolvesFromToolOrUnknown() {
        val registry = ToolRegistry(tools = listOf(FakeTool("sms_send")))
        assertEquals(ToolGroups.groupOf("sms_send"), registry.groupKey("sms_send"))
        // Неизвестный — fail-closed группа UNKNOWN.
        assertEquals(ToolGroups.UNKNOWN, registry.groupKey("no_such_tool"))
    }

    @Test
    fun execute_rethrowsCancellationInsteadOfErrorResult() = runTest {
        // «Стоп» во время вызова тула не должен превращаться
        // в фейковый ToolResult(false) в ленте/таймлайне.
        val cancelling = object : Tool {
            override val schema = ToolSchema(
                name = "cancelling",
                description = "Тестовый инструмент",
                parameters = buildJsonObject { put("type", "object") },
            )
            override suspend fun execute(arguments: JsonObject): ToolResult {
                throw kotlinx.coroutines.CancellationException("stop")
            }
        }
        val registry = ToolRegistry(tools = listOf(cancelling))
        val e = runCatching { registry.execute("cancelling", JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue(e is kotlinx.coroutines.CancellationException)
    }
}
