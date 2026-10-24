package ru.outsideproarts.oassist.core.provider.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Маппинг доменных сообщений в формат Gemini:
 * SYSTEM — в `system_instruction`, ASSISTANT — в роль `model`,
 * TOOL — в `functionResponse`-блоки роли `user`.
 *
 * Нюанс: `functionResponse` требует **имя** функции, а [ChatMessage] с ролью
 * TOOL несёт только `toolCallId`. Имя восстанавливается по соседним ходам
 * ассистента (`toolCalls`: id → name); неизвестный id — fail-closed пустым
 * именем, дальше его ловит ASK-ветка реестра.
 */
internal fun mapGeminiContents(messages: List<ChatMessage>): Pair<GeminiContentDto?, List<GeminiContentDto>> {
    val idToName = messages
        .filter { it.role == ChatRole.ASSISTANT }
        .flatMap { it.toolCalls.orEmpty() }
        .associate { it.id to it.name }
    val system = messages
        .filter { it.role == ChatRole.SYSTEM && it.content.isNotBlank() }
        .joinToString("\n\n") { it.content }
        .takeIf { it.isNotBlank() }
        ?.let { GeminiContentDto(parts = listOf(GeminiPartDto(text = it))) }
    val contents = messages.flatMap { msg ->
        when (msg.role) {
            ChatRole.SYSTEM -> emptyList()
            ChatRole.USER -> listOf(GeminiContentDto(
                role = "user",
                parts = buildList {
                    if (msg.content.isNotEmpty()) add(GeminiPartDto(text = msg.content))
                    msg.audio?.let { audio ->
                        add(GeminiPartDto(inlineData = GeminiBlobDto(
                            mimeType = audio.mimeType,
                            data = audio.wavBase64,
                        )))
                    }
                    msg.images.forEach { img ->
                        add(GeminiPartDto(inlineData = GeminiBlobDto(
                            mimeType = img.mimeType,
                            data = img.imageBase64,
                        )))
                    }
                    // Пустой ход без аудио — как раньше: один пустой текстовый парт.
                    if (isEmpty()) add(GeminiPartDto(text = ""))
                },
            ))
            ChatRole.ASSISTANT -> {
                val parts = buildList {
                    if (msg.content.isNotEmpty()) add(GeminiPartDto(text = msg.content))
                    msg.toolCalls.orEmpty().forEach { call ->
                        add(GeminiPartDto(functionCall = GeminiFunctionCallDto(
                            name = call.name,
                            args = parseGeminiArgs(call.argumentsJson),
                        )))
                    }
                }
                if (parts.isEmpty()) emptyList() else listOf(GeminiContentDto(role = "model", parts = parts))
            }
            ChatRole.TOOL -> buildList {
                add(GeminiContentDto(
                    role = "user",
                    parts = listOf(GeminiPartDto(functionResponse = GeminiFunctionResponseDto(
                        name = idToName[msg.toolCallId] ?: msg.toolCallId.orEmpty(),
                        response = buildJsonObject { put("result", msg.content) },
                    ))),
                ))
                if (msg.images.isNotEmpty()) {
                    add(GeminiContentDto(
                        role = "user",
                        parts = msg.images.map { img ->
                            GeminiPartDto(inlineData = GeminiBlobDto(
                                mimeType = img.mimeType,
                                data = img.imageBase64,
                            ))
                        },
                    ))
                }
            }
        }
    }
    return system to contents
}

/** Схемы OpenAI function calling → `function_declarations`. Пустой список — как null. */
internal fun mapGeminiTools(tools: List<ToolSchemaDto>): List<GeminiToolsDto>? {
    if (tools.isEmpty()) return null
    return listOf(GeminiToolsDto(functionDeclarations = tools.map { tool ->
        GeminiFunctionDeclarationDto(
            name = tool.function.name,
            description = tool.function.description,
            parameters = tool.function.parameters,
        )
    }))
}

/**
 * Извлечь чанки из одного SSE-события: тексты — сразу, `functionCall` —
 * отдельными вызовами с синтетическими id (`call_<n>`).
 * Gemini шлёт каждый `functionCall` целым объектом в своём событии
 * (кускования `args` по событиям, как у OpenAI/Anthropic, здесь нет).
 * Части с `thought: true` — «мысли» модели: уходят в [ChatChunk.Reasoning],
 * иначе они утекали бы в текст ответа (мышлений-гейта на уровне API нет).
 */
internal fun extractGeminiChunk(
    chunk: GeminiStreamChunkDto,
    nextId: Int,
): GeminiExtracted {
    val out = mutableListOf<ChatChunk>()
    var counter = nextId
    var finish: String? = null
    val candidate = chunk.candidates.firstOrNull()
    candidate?.content?.parts.orEmpty().forEach { part ->
        part.text?.takeIf { it.isNotEmpty() }?.let { text ->
            out += if (part.thought) ChatChunk.Reasoning(text) else ChatChunk.Content(text)
        }
        part.functionCall?.let { call ->
            out += ChatChunk.ToolCall(
                id = "call_${counter++}",
                name = call.name,
                argumentsJson = ProviderJson.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    call.args ?: JsonObject(emptyMap()),
                ),
                index = counter - 1 - nextId,
            )
        }
    }
    candidate?.finishReason?.let { finish = it }
    return GeminiExtracted(out, counter, finish)
}

internal data class GeminiExtracted(
    val chunks: List<ChatChunk>,
    val nextId: Int,
    val finishReason: String?,
)

private fun parseGeminiArgs(argumentsJson: String) =
    runCatching { ProviderJson.parseToJsonElement(argumentsJson).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }
