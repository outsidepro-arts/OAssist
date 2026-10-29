package ru.outsideproarts.oassist.core.device

import kotlin.coroutines.coroutineContext
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

class DiagnosticPrivExecutor(private val delegate: PrivExecutor) : PrivExecutor {
    override val level get() = delegate.level

    private suspend fun <T> execute(action: String, block: suspend () -> Result<T>): Result<T> {
        val id = Diagnostics.newOperationId()
        val parent = coroutineContext[DiagnosticContext]?.operationId
        Diagnostics.record(DiagnosticKind.DEVICE, id, parent, mapOf("action" to action, "phase" to "start", "executor" to level.name))
        return try {
            block().also { result ->
                Diagnostics.record(DiagnosticKind.DEVICE, id, parent, mapOf("action" to action, "phase" to "finish", "success" to result.isSuccess.toString(), "errorType" to (result.exceptionOrNull()?.javaClass?.simpleName ?: "")))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            Diagnostics.record(DiagnosticKind.OPERATION_CANCELLED, id, parent)
            throw cancel
        } catch (error: Exception) {
            Diagnostics.record(DiagnosticKind.OPERATION_ERROR, id, parent, mapOf("errorType" to error.javaClass.simpleName))
            throw error
        }
    }

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int) = execute("exec") { delegate.exec(cmd, timeoutMs, maxBytes) }
    override suspend fun getSetting(namespace: String, key: String) = execute("get_setting") { delegate.getSetting(namespace, key) }
    override suspend fun putSetting(namespace: String, key: String, value: String) = execute("put_setting") { delegate.putSetting(namespace, key, value) }
    override suspend fun inputTap(x: Int, y: Int) = execute("tap") { delegate.inputTap(x, y) }
    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = execute("swipe") { delegate.inputSwipe(fromX, fromY, toX, toY, durationMs) }
    override suspend fun inputKeyevent(keyCode: Int) = execute("keyevent") { delegate.inputKeyevent(keyCode) }
}
