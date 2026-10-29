package ru.outsideproarts.oassist.core.model

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.*

class DiagnosticLaunchTest {
    @Test fun rootOperationHasContextAndTerminalEvent() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val previous = Diagnostics.swapForTest(object : DiagnosticRecorder {
            override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events.add(event) }
            override fun health() = DiagnosticHealth(true, true, 0, 0)
        })
        try {
            launchDiagnostic("CHAT", "run") {
                assertEquals("run", coroutineContext[DiagnosticContext]?.operationId)
            }.join()
            assertEquals(listOf(DiagnosticKind.OPERATION_START, DiagnosticKind.OPERATION_END), events.map { it.kind })
            assertEquals("CHAT", events.first().attributes["mode"])
            assertEquals("run", events.last().operationId)
        } finally { Diagnostics.swapForTest(previous) }
    }
}
