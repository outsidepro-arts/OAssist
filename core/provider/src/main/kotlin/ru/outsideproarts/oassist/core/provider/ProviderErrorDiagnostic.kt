package ru.outsideproarts.oassist.core.provider

import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder
import ru.outsideproarts.oassist.core.model.diagnostics.emit

internal suspend fun recordProviderHttpError(
    diagnostics: DiagnosticRecorder,
    operation: String?,
    error: Throwable,
    secrets: Collection<String>,
) {
    val seen = mutableSetOf<Throwable>()
    var cause: Throwable? = error
    var sseResponse: HttpResponse? = null
    var responseError: ResponseException? = null
    while (cause != null && seen.add(cause)) {
        if (cause is SSEClientException && sseResponse == null) sseResponse = cause.response
        if (cause is ResponseException) { responseError = cause; break }
        cause = cause.cause
    }
    val response = responseError?.response ?: sseResponse ?: return
    val body = try {
        withTimeoutOrNull(1000) {
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            val result = java.io.ByteArrayOutputStream()
            while (true) {
                val n = channel.readAvailable(buffer, 0, minOf(buffer.size, 65537 - result.size()))
                if (n < 0) break
                if (n == 0) continue
                result.write(buffer, 0, n)
                if (result.size() > 65536) return@withTimeoutOrNull null
            }
            result.toByteArray().toString(Charsets.UTF_8)
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    diagnostics.emit(DiagnosticKind.PROVIDER_ERROR, operationId = operation ?: ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics.newOperationId(),
        attributes = mapOf("stage" to "http_error_body", "status" to response.status.value.toString(),
            "body_omitted" to (body == null).toString()),
        content = body, secrets = secrets)
}
