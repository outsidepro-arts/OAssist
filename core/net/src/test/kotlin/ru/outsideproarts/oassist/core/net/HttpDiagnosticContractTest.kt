package ru.outsideproarts.oassist.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder

class HttpDiagnosticContractTest {
    private class Recording : DiagnosticRecorder {
        val events = CopyOnWriteArrayList<DiagnosticEvent>()
        override fun record(event: DiagnosticEvent, secrets: Collection<String>) { events.add(event) }
        override fun health() = DiagnosticHealth(true, true, 0, 0)
    }

    private fun client(engine: MockEngine, recording: Recording) = HttpClient(engine) {
        installRetryPolicy(recording, maxDelayMs = 1, randomizationMs = 0)
        expectSuccess = true
    }

    @Test fun everyRetryAttemptHasCorrelatedRequestAndTerminalEvent() = runTest {
        val recording = Recording()
        var calls = 0
        val http = client(MockEngine {
            when (++calls) {
                1 -> throw IOException("connection reset")
                2 -> respond("busy", HttpStatusCode.ServiceUnavailable)
                else -> respond("ok", HttpStatusCode.OK)
            }
        }, recording)
        try {
            assertEquals(HttpStatusCode.OK, http.get("https://example.test/models") {
                attributes.put(DiagnosticOperationKey, "catalog")
                attributes.put(DiagnosticAttemptKey, AtomicInteger())
            }.status)
            val requests = recording.events.filter { it.kind == DiagnosticKind.HTTP_REQUEST }
            assertEquals(listOf("1", "2", "3"), requests.map { it.attributes["attempt"] })
            val terminal = recording.events.filter { it.kind != DiagnosticKind.HTTP_REQUEST }
            assertEquals(3, terminal.size)
            assertEquals(listOf("true", "true", "false"), terminal.map { it.attributes["retry"] })
            requests.zip(terminal).forEach { (request, result) ->
                assertEquals("catalog", request.parentOperationId)
                assertEquals(request.operationId, result.operationId)
                assertEquals(request.parentOperationId, result.parentOperationId)
                assertEquals(request.attributes["attempt"], result.attributes["attempt"])
            }
            assertEquals(DiagnosticKind.HTTP_RESPONSE, terminal.last().kind)
            assertEquals("200", terminal.last().attributes["status"])
        } finally { http.close() }
    }

    @Test fun exhaustedRetriesAndNonIdempotentFailureStillHaveFinalDiagnostics() = runTest {
        for (noRetry in listOf(false, true)) {
            val recording = Recording()
            var calls = 0
            val http = client(MockEngine { calls++; respond("busy", HttpStatusCode.ServiceUnavailable) }, recording)
            try {
                assertTrue(runCatching {
                    http.get("https://example.test/tools") {
                        attributes.put(DiagnosticOperationKey, "tool")
                        if (noRetry) attributes.put(NoAutoRetry, Unit)
                    }
                }.isFailure)
                assertEquals(if (noRetry) 1 else 3, calls)
                val requests = recording.events.filter { it.kind == DiagnosticKind.HTTP_REQUEST }
                val terminal = recording.events.filter { it.kind == DiagnosticKind.HTTP_RESPONSE || it.kind == DiagnosticKind.HTTP_ERROR }
                assertEquals(calls, requests.size)
                assertEquals(calls, terminal.size)
                assertEquals("false", terminal.last().attributes["retry"])
                assertEquals("503", terminal.last().attributes["status"])
                assertEquals(requests.last().operationId, terminal.last().operationId)
            } finally { http.close() }
        }
    }

    @Test fun cancellationRecordsTerminalWithoutRetrying() = runTest {
        val recording = Recording()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val http = client(MockEngine { calls++; started.complete(Unit); awaitCancellation() }, recording)
        try {
            val pending = async {
                http.get("https://example.test/pending") { attributes.put(DiagnosticOperationKey, "cancelled") }
            }
            started.await()
            pending.cancelAndJoin()
            assertEquals(1, calls)
            val request = recording.events.single { it.kind == DiagnosticKind.HTTP_REQUEST }
            val terminal = recording.events.single { it.kind == DiagnosticKind.HTTP_ERROR }
            assertEquals(request.operationId, terminal.operationId)
            assertEquals("cancelled", terminal.parentOperationId)
            assertEquals("true", terminal.attributes["cancelled"])
            assertEquals("false", terminal.attributes["retry"])
        } finally { http.close() }
    }
}
