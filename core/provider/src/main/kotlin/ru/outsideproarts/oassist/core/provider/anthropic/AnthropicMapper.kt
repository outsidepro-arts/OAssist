package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ImagePart
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Маппинг доменных сообщений в формат Anthropic Messages API:
 * SYSTEM уходит в отдельное поле `system`, TOOL — в `tool_result`-блоки
 * роли `user`, ходы ассистента с вызовами — в `tool_use`-блоки.
 * Аудио-вход API не умеет: голосовой ход с аудио — fail-closed исключением
 * (UI туда не доводит: без флага AUDIO_IN уходит в STT-текст).
 * Неподдерживаемых ролей у [ChatRole] нет — ветка exhaustive.
 *
 * [thinkingEnabled] — effective thinking, including validated native continuation: assistant-сообщения
 * с [ChatMessage.reasoning] возвращают исходные блоки того же origin без изменений.
 * Legacy-истории содержат только одну пару text/signature: потерянные границы
 * и redacted-блоки из неё восстановить нельзя. Выключенный thinking не отправляет
 * reasoning; исходная история при этом не изменяется.
 */
internal fun mapAnthropicMessages(
    messages: List<ChatMessage>,
    thinkingEnabled: Boolean = false,
    replayOrigin: String? = null,
): Pair<String?, List<AnthropicMessageDto>> {
    messages.firstOrNull { it.audio != null }?.let {
        throw ProviderError.NotSupported("audio input")
    }
    val system = messages
        .filter { it.role == ChatRole.SYSTEM && it.content.isNotBlank() }
        .joinToString("\n\n") { it.content }
        .takeIf { it.isNotBlank() }
    val out = messages.mapNotNull { msg ->
        when (msg.role) {
            ChatRole.SYSTEM -> null
            ChatRole.USER -> userContentDto(msg)
            ChatRole.ASSISTANT -> {
                val calls = msg.toolCalls.orEmpty()
                val reasoning = msg.reasoning
                val replay = if (thinkingEnabled) matchingReplayContent(msg, replayOrigin) else null
                val thinkingBlock = if (
                    thinkingEnabled && reasoning != null &&
                    reasoning.anthropicReplay == null && reasoning.signature != null
                ) {
                    buildJsonObject {
                        put("type", "thinking")
                        put("thinking", reasoning.text)
                        put("signature", reasoning.signature)
                    }
                } else {
                    null
                }
                if (replay != null) {
                    AnthropicMessageDto("assistant", replay)
                } else if (calls.isEmpty() && thinkingBlock == null) {
                    AnthropicMessageDto("assistant", JsonPrimitive(msg.content))
                } else {
                    val blocks = buildList {
                        thinkingBlock?.let { add(it) }
                        if (msg.content.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            })
                        }
                        calls.forEach { call ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", parseAnthropicInput(call.argumentsJson))
                            })
                        }
                    }
                    AnthropicMessageDto("assistant", JsonArray(blocks))
                }
            }
            ChatRole.TOOL -> AnthropicMessageDto(
                role = "user",
                content = JsonArray(listOf(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId.orEmpty())
                    if (msg.images.isEmpty()) {
                        put("content", msg.content)
                    } else {
                        put("content", contentBlocks(msg.content, msg.images))
                    }
                })),
            )
        }
    }
    return system to out
}

internal fun matchingReplayContent(message: ChatMessage, origin: String?): JsonArray? =
    message.reasoning?.anthropicReplay?.takeIf { it.origin == origin }
        ?.let { completeReplayContent(message, it.blocksJson) }

internal fun hasMatchingThinkingReplay(messages: List<ChatMessage>, origin: String): Boolean = messages.any { message ->
    if (message.role != ChatRole.ASSISTANT) return@any false
    val blocks = matchingReplayContent(message, origin)?.map { it.jsonObject } ?: return@any false
    val thinking = blocks.filter { it["type"]?.jsonPrimitive?.contentOrNull in setOf("thinking", "redacted_thinking") }
    thinking.isNotEmpty() && thinking.all { block ->
        val field = if (block["type"]?.jsonPrimitive?.contentOrNull == "thinking") "signature" else "data"
        val value = block[field] as? JsonPrimitive
        value?.isString == true && !value.contentOrNull.isNullOrBlank()
    }
}

/** Never let an opaque snapshot override edited text or remapped/removed tool calls. */
private fun completeReplayContent(message: ChatMessage, json: String): JsonArray? = runCatching {
    val blocks = ProviderJson.parseToJsonElement(json) as? JsonArray ?: return null
    val objects = blocks.map { it.jsonObject }
    val text = objects.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
        .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    if (text != message.content) return null
    val calls = objects.filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
    if (calls.size != message.toolCalls.orEmpty().size) return null
    if (!calls.zip(message.toolCalls.orEmpty()).all { (block, call) ->
        block["id"]?.jsonPrimitive?.contentOrNull == call.id &&
            block["name"]?.jsonPrimitive?.contentOrNull == call.name &&
            block["input"] == ProviderJson.parseToJsonElement(call.argumentsJson)
    }) return null
    blocks
}.getOrNull()

/** Схемы OpenAI function calling → Anthropic `tools` (`input_schema`). Пустой список — как null. */
internal fun mapAnthropicTools(tools: List<ToolSchemaDto>): List<AnthropicToolDto>? {
    if (tools.isEmpty()) return null
    return tools.map { tool ->
        AnthropicToolDto(
            name = tool.function.name,
            description = tool.function.description,
            inputSchema = tool.function.parameters ?: JsonObject(emptyMap()),
        )
    }
}

private fun parseAnthropicInput(argumentsJson: String) =
    runCatching { ProviderJson.parseToJsonElement(argumentsJson).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }

/** User-сообщение: строкой без образов, блоками (text + image) с образами. */
private fun userContentDto(msg: ChatMessage): AnthropicMessageDto =
    if (msg.images.isEmpty()) {
        AnthropicMessageDto("user", JsonPrimitive(msg.content))
    } else {
        AnthropicMessageDto("user", contentBlocks(msg.content, msg.images))
    }

private fun contentBlocks(text: String, images: List<ImagePart>): JsonArray =
    JsonArray(buildList {
        if (text.isNotEmpty()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        images.forEach { img ->
            add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", img.mimeType)
                    put("data", img.imageBase64)
                })
            })
        }
    })
