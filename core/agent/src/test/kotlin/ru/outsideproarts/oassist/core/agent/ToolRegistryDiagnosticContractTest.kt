package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.*

class ToolRegistryDiagnosticContractTest {
    @Test fun recordsSuccessErrorCancellationAndCorrelatesThem() = runBlocking {
        val events = mutableListOf<Pair<DiagnosticEvent, Collection<String>>>()
        val recorder = object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events += event to secrets.toList() }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        }
        for (scenario in listOf("success", "error", "cancel")) {
            val tool = object : Tool {
                override val schema = ToolSchema("tool-$scenario", "test", JsonObject(emptyMap()))
                override fun diagnosticSecrets() = listOf("credential-value")
                override suspend fun execute(arguments: JsonObject): ToolResult = when (scenario) {
                    "error" -> throw IllegalStateException("failure")
                    "cancel" -> throw CancellationException("cancel")
                    else -> ToolResult(true, "ok")
                }
            }
            val registry = ToolRegistry(listOf(tool), diagnostics = recorder)
            try { withContext(DiagnosticContext("parent")) { registry.execute(tool.schema.name, JsonObject(emptyMap())) } }
            catch (_: CancellationException) { assertEquals("cancel", scenario) }
        }
        assertEquals(6, events.size)
        for ((index, kind) in listOf(DiagnosticKind.OPERATION_END, DiagnosticKind.OPERATION_ERROR,
            DiagnosticKind.OPERATION_CANCELLED).withIndex()) {
            val start = events[index * 2]
            val terminal = events[index * 2 + 1]
            assertEquals(DiagnosticKind.OPERATION_START, start.first.kind)
            assertEquals("parent", start.first.parentOperationId)
            assertEquals(kind, terminal.first.kind)
            assertEquals(start.first.operationId, terminal.first.operationId)
            assertTrue("credential-value" in start.second)
            assertTrue("credential-value" in terminal.second)
        }
    }
}
