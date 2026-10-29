package ru.outsideproarts.oassist.core.model.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun CoroutineScope.launchDiagnostic(mode: String, operationId: String, block: suspend CoroutineScope.() -> Unit): Job =
    launch(DiagnosticContext(operationId)) {
        val started = System.nanoTime()
        Diagnostics.record(DiagnosticKind.OPERATION_START, operationId, attributes = mapOf("mode" to mode, "action" to "run"))
        try {
            block()
            Diagnostics.record(DiagnosticKind.OPERATION_END, operationId, attributes = mapOf("durationNanos" to (System.nanoTime() - started).toString()))
        } catch (cancel: CancellationException) {
            Diagnostics.record(DiagnosticKind.OPERATION_CANCELLED, operationId)
            throw cancel
        } catch (error: Throwable) {
            Diagnostics.record(DiagnosticKind.OPERATION_ERROR, operationId, attributes = mapOf("errorType" to error.javaClass.simpleName))
            throw error
        }
    }
