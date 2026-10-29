package ru.outsideproarts.oassist.core.provider

import kotlinx.serialization.json.*
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.model.diagnostics.emit

/**
 * Retains complete bounded JSON events until the stream ends so credentials split
 * across adjacent delta values can be removed before events reach the recorder.
 */
internal class SseDiagnosticCapture(
    private val endpoint: EndpointConfig,
    private val context: ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext?,
) {
    private val parentOperation get() = context?.operationId
    private val frames = mutableListOf<JsonElement>()
    private var bytes = 0
    private var truncated = false
    private var malformed = 0

    fun add(raw: String) {
        if (truncated) return
        val size = raw.toByteArray(Charsets.UTF_8).size
        if (bytes + size > LIMIT) { truncated = true; return }
        bytes += size
        val frame = runCatching { ProviderJson.parseToJsonElement(raw) }.getOrNull()
        if (frame == null) {
            malformed++
            endpoint.diagnostics.emit(DiagnosticKind.PROVIDER_ERROR, parentOperationId = parentOperation,
                attributes = mapOf("stage" to "sse_decode", "reason" to "malformed_event"))
            frames += JsonPrimitive("[malformed event omitted]")
        } else frames += frame
    }

    fun flush() {
        val secrets = (endpoint.diagnosticSecrets() + context?.secrets.orEmpty()).filter { it.isNotEmpty() }
        val fields = linkedMapOf<String, MutableList<String>>()
        fun collect(value: JsonElement, path: String) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> collect(child, "$path/$key") }
                is JsonArray -> value.forEach { child -> collect(child, "$path/*") }
                is JsonPrimitive -> if (value.isString) fields.getOrPut(path) { mutableListOf() }.add(value.content)
            }
        }
        frames.forEach { collect(it, "") }
        val masked = fields.mapValues { (_, values) ->
            val joined = values.joinToString("")
            val marks = BooleanArray(joined.length)
            secrets.forEach { secret ->
                var start = 0
                while (start <= joined.length - secret.length) {
                    val found = joined.indexOf(secret, start)
                    if (found < 0) break
                    for (i in found until found + secret.length) marks[i] = true
                    start = found + 1
                }
            }
            var offset = 0
            values.map { value ->
                val result = buildString(value.length) {
                    value.forEachIndexed { i, char -> append(if (marks[offset + i]) '•' else char) }
                }
                offset += value.length
                result
            }
        }
        val indices = mutableMapOf<String, Int>()
        fun rebuild(value: JsonElement, path: String): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) -> rebuild(child, "$path/$key") })
            is JsonArray -> JsonArray(value.map { rebuild(it, "$path/*") })
            is JsonPrimitive -> if (!value.isString) value else {
                val index = indices[path] ?: 0
                indices[path] = index + 1
                JsonPrimitive(masked.getValue(path)[index])
            }
        }
        val cleaned = JsonArray(frames.map { rebuild(it, "") }).toString()
        endpoint.diagnostics.emit(DiagnosticKind.PROVIDER_RESPONSE, parentOperationId = parentOperation,
            attributes = mapOf("format" to "sse_events", "truncated" to truncated.toString(),
                "malformed_omitted" to malformed.toString()),
            content = cleaned, secrets = secrets)
        frames.clear()
    }

    companion object { private const val LIMIT = 4 * 1024 * 1024 }
}
