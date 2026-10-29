package ru.outsideproarts.oassist.core.device

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.*

class DiagnosticPrivExecutorTest {
    @Test fun deniedActionStillHasLinkedTerminalDiagnostic() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events.add(event) }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        try {
            val result = withContext(DiagnosticContext("run")) {
                DiagnosticPrivExecutor(NoopExecutor()).inputTap(10, 20)
            }
            assertTrue(result.isFailure)
            val records = events.filter { it.kind == DiagnosticKind.DEVICE }
            assertEquals(2, records.size)
            assertEquals(records.first().operationId, records.last().operationId)
            assertEquals("run", records.first().parentOperationId)
            assertEquals("false", records.last().attributes["success"])
        } finally { Diagnostics.swapForTest(previous) }
    }
}
