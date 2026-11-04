package ru.outsideproarts.oassist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModeCatalogControllerTest {
    private fun models(id: String) = listOf(ModelInfo(id))

    @Test
    fun lateRefreshAndProbeCannotReplaceSelectedProvider() = runTest {
        val a = CompletableDeferred<List<ModelInfo>>()
        val probe = CompletableDeferred<List<ModelInfo>>()
        val controller = ModeCatalogController(this, load = { models(it) }, reportError = { _, error -> throw AssertionError(error) })
        controller.select("A")
        runCurrent()
        controller.refresh("A", { a.await() }, {}, { throw AssertionError(it) })
        runCurrent()
        controller.probe("A", { probe.await() }, {}, { throw AssertionError(it) })
        runCurrent()
        controller.select("B")
        assertEquals(emptyList<ModelInfo>(), controller.models)
        runCurrent()
        assertEquals(models("B"), controller.models)
        a.complete(models("old refresh"))
        probe.complete(models("old probe"))
        runCurrent()
        assertEquals(models("B"), controller.models)
        assertFalse(controller.isLoading)
        assertFalse(controller.isProbing)
    }

    @Test
    fun latestRefreshWinsAndClosedEditorRejectsLateResult() = runTest {
        val first = CompletableDeferred<List<ModelInfo>>()
        val second = CompletableDeferred<List<ModelInfo>>()
        val late = CompletableDeferred<List<ModelInfo>>()
        val controller = ModeCatalogController(this, load = { models(it) }, reportError = { _, error -> throw AssertionError(error) })
        controller.select("A")
        runCurrent()
        controller.refresh("A", { first.await() }, {}, { throw AssertionError(it) })
        controller.refresh("A", { second.await() }, {}, { throw AssertionError(it) })
        runCurrent()
        second.complete(models("new"))
        runCurrent()
        first.complete(models("old"))
        runCurrent()
        assertEquals(models("new"), controller.models)
        controller.probe("A", { late.await() }, { throw AssertionError("closed callback") }, { throw AssertionError(it) })
        runCurrent()
        controller.close()
        late.complete(models("late"))
        runCurrent()
        assertEquals(models("new"), controller.models)
    }
    @Test
    fun failedUpdateKeepsStoredValueAndReportsFailure() = runTest {
        var stored = models("original")
        val failures = mutableListOf<Throwable>()
        val diagnostics = mutableListOf<Throwable>()
        val failure = IllegalStateException("store failed")
        val storeResult = CompletableDeferred<Unit>()
        val controller = ModeCatalogController(this, load = { stored }, reportError = { _, error -> diagnostics += error })
        controller.select("A")
        runCurrent()
        controller.update("A", onFailure = { failures += it }) {
            storeResult.await()
            stored = models("changed")
            stored
        }
        runCurrent()
        storeResult.completeExceptionally(failure)
        runCurrent()
        assertEquals(models("original"), stored)
        assertEquals(models("original"), controller.models)
        assertEquals(listOf("store failed"), failures.map { it.message })
        assertEquals(listOf("store failed"), diagnostics.map { it.message })
    }

    @Test
    fun lateUpdateErrorsAreDiagnosedWithoutShowingThemInAnotherEditor() = runTest {
        val pendingA = CompletableDeferred<List<ModelInfo>>()
        val pendingB = CompletableDeferred<List<ModelInfo>>()
        val shown = mutableListOf<Throwable>()
        val diagnostics = mutableListOf<Throwable>()
        val controller = ModeCatalogController(this, load = { models(it) }, reportError = { _, error -> diagnostics += error })
        controller.select("A")
        runCurrent()
        controller.update("A", onFailure = { shown += it }) { pendingA.await() }
        runCurrent()
        controller.select("B")
        runCurrent()
        val failureA = IllegalStateException("A")
        pendingA.completeExceptionally(failureA)
        runCurrent()
        assertEquals(models("B"), controller.models)
        controller.update("B", onFailure = { shown += it }) { pendingB.await() }
        runCurrent()
        controller.close()
        val failureB = IllegalStateException("B")
        pendingB.completeExceptionally(failureB)
        runCurrent()
        assertEquals(emptyList<Throwable>(), shown)
        assertEquals(listOf("A", "B"), diagnostics.map { it.message })
    }
    @Test
    fun updateFailureReachesDiagnosticsRecorderWithoutSensitiveData() = runTest {
        val events = mutableListOf<ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent>()
        val recorder = object : ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder {
            override fun record(event: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent, secrets: Collection<String>) {
                events += event
            }
            override fun health() = ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticHealth(true, true, 0, 0)
        }
        val previous = ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.swapForTest(recorder)
        try {
            val controller = ModeCatalogController(this, load = { models("safe") })
            controller.select("secret-provider-id")
            runCurrent()
            controller.update("secret-provider-id", onFailure = {}) {
                throw IllegalStateException("secret exception text")
            }
            runCurrent()
            val event = events.single()
            assertEquals(ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind.PROVIDER_CATALOG, event.kind)
            assertEquals(mapOf("operation" to "update", "errorType" to "IllegalStateException"), event.attributes)
            assertEquals(null, event.content)
            assertFalse(event.toString().contains("secret"))
        } finally {
            ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.swapForTest(previous)
        }
    }
}
