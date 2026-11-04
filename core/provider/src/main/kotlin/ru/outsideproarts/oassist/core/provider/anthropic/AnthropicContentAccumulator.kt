package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.json.*
import ru.outsideproarts.oassist.core.provider.ProviderError

/** Assemble complete blocks, retaining unknown start fields and exact text/signature bytes. */
internal class AnthropicContentAccumulator {
    private data class Block(val fields: MutableMap<String, JsonElement>, val input: StringBuilder = StringBuilder(), var closed: Boolean = false)
    private val blocks = sortedMapOf<Int, Block>()

    fun start(index: Int, value: JsonObject) {
        if (blocks.containsKey(index)) throw ProviderError.Protocol("anthropic: duplicate content block")
        blocks[index] = Block(value.toMutableMap())
    }

    fun delta(index: Int, delta: AnthropicDelta) {
        val block = blocks[index] ?: throw ProviderError.Protocol("anthropic: delta without content block")
        if (block.closed) throw ProviderError.Protocol("anthropic: delta after content block stop")
        val field = when (delta.type) {
            "text_delta" -> "text" to delta.text
            "thinking_delta" -> "thinking" to delta.thinking
            "signature_delta" -> "signature" to delta.signature
            "input_json_delta" -> { block.input.append(delta.partialJson.orEmpty()); return }
            else -> return
        }
        block.fields[field.first] = JsonPrimitive(block.fields[field.first]?.jsonPrimitive?.content.orEmpty() + field.second.orEmpty())
    }

    fun stop(index: Int) {
        val block = blocks[index] ?: throw ProviderError.Protocol("anthropic: stop without content block")
        if (block.closed) throw ProviderError.Protocol("anthropic: duplicate content block stop")
        if (block.input.isNotEmpty()) {
            block.fields["input"] = try { Json.parseToJsonElement(block.input.toString()).jsonObject }
            catch (_: Exception) { throw ProviderError.Protocol("anthropic: incomplete tool input") }
        }
        block.closed = true
    }

    fun complete(): JsonArray {
        if (blocks.values.any { !it.closed }) throw ProviderError.Protocol("anthropic: incomplete content block")
        for (block in blocks.values) {
            when (block.fields["type"]?.jsonPrimitive?.contentOrNull) {
                "thinking" -> if (block.fields["thinking"] !is JsonPrimitive ||
                    block.fields["signature"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) {
                    throw ProviderError.Protocol("anthropic: unsigned thinking block")
                }
                "redacted_thinking" -> if (block.fields["data"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) {
                    throw ProviderError.Protocol("anthropic: missing redacted thinking data")
                }
            }
        }
        return JsonArray(blocks.values.map { JsonObject(it.fields) })
    }
}
