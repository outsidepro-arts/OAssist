package ru.outsideproarts.oassist.core.model.diagnostics

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
enum class DiagnosticKind {
    OPERATION_START, OPERATION_END, OPERATION_ERROR, OPERATION_CANCELLED,
    AGENT_ROUND, TOOL_SCHEMA, TOOL_CALL, TOOL_RESULT,
    PROVIDER_REQUEST, PROVIDER_RESPONSE, PROVIDER_ERROR, PROVIDER_CATALOG,
    ROUTING, HTTP_REQUEST, HTTP_RESPONSE, HTTP_ERROR, MCP, SPEECH, DEVICE, STORAGE, LIFECYCLE,
}

@Serializable
data class DiagnosticEvent(
    val kind: DiagnosticKind,
    val operationId: String,
    val parentOperationId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val content: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val monotonicNanos: Long = System.nanoTime(),
    val sequence: Long = 0,
    val truncated: Boolean = false,
)

data class DiagnosticHealth(
    val available: Boolean,
    val contentEnabled: Boolean,
    val droppedEvents: Long,
    val storedBytes: Long,
    val lastError: String? = null,
)

interface DiagnosticRecorder {
    /** Sanitization must happen synchronously, before retaining the event. */
    fun record(event: DiagnosticEvent, secrets: Collection<String> = emptyList())
    fun health(): DiagnosticHealth
}

class DiagnosticContext(
    val operationId: String,
    val parentOperationId: String? = null,
    val secrets: Collection<String> = emptyList(),
) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticContext>
}

object Diagnostics : DiagnosticRecorder {
    @Volatile private var recorder: DiagnosticRecorder? = null
    private val unavailableEvents = AtomicLong()

    fun install(recorder: DiagnosticRecorder) { this.recorder = recorder }
    fun swapForTest(recorder: DiagnosticRecorder?): DiagnosticRecorder? = synchronized(this) {
        this.recorder.also { this.recorder = recorder }
    }
    fun newOperationId(): String = UUID.randomUUID().toString()
    override fun health(): DiagnosticHealth = recorder?.health()
        ?: DiagnosticHealth(false, false, unavailableEvents.get(), 0, "recorder_unavailable")

    fun record(
        kind: DiagnosticKind,
        operationId: String = newOperationId(),
        parentOperationId: String? = null,
        attributes: Map<String, String> = emptyMap(),
        content: String? = null,
        secrets: Collection<String> = emptyList(),
    ) {
        record(DiagnosticEvent(kind, operationId, parentOperationId, attributes, content), secrets)
    }

    override fun record(event: DiagnosticEvent, secrets: Collection<String>) {
        val target = recorder
        if (target == null) {
            unavailableEvents.incrementAndGet()
            return
        }
        try {
            target.record(event, secrets)
        } catch (_: Exception) {
            unavailableEvents.incrementAndGet()
        }
    }
}

fun DiagnosticRecorder.emit(
    kind: DiagnosticKind,
    operationId: String = Diagnostics.newOperationId(),
    parentOperationId: String? = null,
    attributes: Map<String, String> = emptyMap(),
    content: String? = null,
    secrets: Collection<String> = emptyList(),
) = record(DiagnosticEvent(kind, operationId, parentOperationId, attributes, content), secrets)
