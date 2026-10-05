package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
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
 */
internal fun mapAnthropicMessages(messages: List<ChatMessage>): Pair<String?, List<AnthropicMessageDto>> {
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
            ChatRole.USER -> AnthropicMessageDto("user", JsonPrimitive(msg.content))
            ChatRole.ASSISTANT -> {
                val calls = msg.toolCalls.orEmpty()
                if (calls.isEmpty()) {
                    AnthropicMessageDto("assistant", JsonPrimitive(msg.content))
                } else {
                    val blocks = buildList {
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
                    put("content", msg.content)
                })),
            )
        }
    }
    return system to out
}

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
