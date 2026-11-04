package ru.outsideproarts.oassist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

internal class CatalogProviderMissing : Exception()

/** Owns catalog results for the provider currently selected in one mode editor. */
internal class ModeCatalogController(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> List<ModelInfo>,
    private val reportError: (String, Throwable) -> Unit = { operation, error ->
        Diagnostics.record(
            DiagnosticKind.PROVIDER_CATALOG,
            attributes = mapOf("operation" to operation, "errorType" to error.javaClass.simpleName),
        )
    },
) {
    var models by mutableStateOf(emptyList<ModelInfo>())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isProbing by mutableStateOf(false)
        private set
    private var providerId: String? = null
    private var revision = 0L
    private var closed = false

    fun select(id: String) {
        if (closed) return
        if (id == providerId) {
            if (id.isNotBlank()) run(id, "load", operation = { load(id) })
            return
        }
        providerId = id
        models = emptyList()
        if (id.isBlank()) {
            revision++
            isLoading = false
            isProbing = false
            return
        }
        run(id, "load", operation = { load(id) })
    }

    fun refresh(id: String, operation: suspend () -> List<ModelInfo>, onSuccess: (List<ModelInfo>) -> Unit, onFailure: (Throwable) -> Unit) {
        run(id, "refresh", loading = true, operation = operation, onSuccess = onSuccess, onFailure = onFailure)
    }

    fun probe(id: String, operation: suspend () -> List<ModelInfo>, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        run(id, "probe", probing = true, operation = operation, onSuccess = { onSuccess() }, onFailure = onFailure)
    }

    fun update(id: String, onFailure: (Throwable) -> Unit, operation: suspend () -> List<ModelInfo>) {
        run(id, "update", operation = operation, onFailure = onFailure)
    }

    fun close() {
        closed = true
        revision++
    }

    private fun run(
        id: String,
        operationName: String,
        loading: Boolean = false,
        probing: Boolean = false,
        operation: suspend () -> List<ModelInfo>,
        onSuccess: (List<ModelInfo>) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) {
        if (closed || id != providerId) return
        val request = ++revision
        isLoading = loading
        isProbing = probing
        scope.launch {
            try {
                val result = operation()
                if (!closed && id == providerId && request == revision) {
                    models = result
                    onSuccess(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(operationName, e)
                if (!closed && id == providerId && request == revision) {
                    if (loading) models = emptyList()
                    onFailure(e)
                }
            } finally {
                if (!closed && id == providerId && request == revision) {
                    isLoading = false
                    isProbing = false
                }
            }
        }
    }
}
