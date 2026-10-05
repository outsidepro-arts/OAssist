package ru.outsideproarts.oassist.core.provider.gemini

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderJson
import ru.outsideproarts.oassist.core.provider.ToolCallRef
import ru.outsideproarts.oassist.core.provider.openai.ToolFunctionSchemaDto
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class GeminiMapperTest {

    @Test
    fun systemMessage_foldsIntoSystemInstruction() {
        val (system, contents) = mapGeminiContents(listOf(
            ChatMessage(ChatRole.SYSTEM, "Ты ассистент"),
            ChatMessage(ChatRole.USER, "Привет"),
        ))
        assertEquals("Ты ассистент", system!!.parts.single().text)
        assertEquals(1, contents.size)
        assertEquals("user", contents[0].role)
    }

    @Test
    fun noSystem_yieldsNull() {
        val (system, _) = mapGeminiContents(listOf(ChatMessage(ChatRole.USER, "hi")))
        assertNull(system)
    }

    @Test
    fun assistantWithToolCalls_mapsToModelWithFunctionCall() {
        val (_, contents) = mapGeminiContents(listOf(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Смотрю",
                toolCalls = listOf(ToolCallRef("call_0", "device_state", "{\"detailed\":true}")),
            ),
        ))
        val msg = contents.single()
        assertEquals("model", msg.role)
        assertEquals("Смотрю", msg.parts[0].text)
        val call = msg.parts[1].functionCall!!
        assertEquals("device_state", call.name)
        assertEquals(true, call.args!!.jsonObject["detailed"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun toolResult_resolvesNameFromAssistantHistory() {
        val (_, contents) = mapGeminiContents(listOf(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCallRef("call_0", "device_state", "{}")),
            ),
            ChatMessage(ChatRole.TOOL, "torch=off", toolCallId = "call_0"),
        ))
        val msg = contents.last()
        assertEquals("user", msg.role)
        val response = msg.parts.single().functionResponse!!
        assertEquals("device_state", response.name)
        assertEquals("torch=off", response.response.jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResult_unknownId_failsClosedWithIdAsName() {
        val (_, contents) = mapGeminiContents(listOf(
            ChatMessage(ChatRole.TOOL, "x", toolCallId = "call_7"),
        ))
        val response = contents.single().parts.single().functionResponse!!
        assertEquals("call_7", response.name)
    }

    @Test
    fun tools_mapToFunctionDeclarations() {
        val tools = mapGeminiTools(listOf(
            ToolSchemaDto(function = ToolFunctionSchemaDto(name = "device_state", description = "Снимок")),
        ))
        val decl = tools!!.single().functionDeclarations.single()
        assertEquals("device_state", decl.name)
        assertEquals("Снимок", decl.description)
    }

    @Test
    fun emptyTools_mapToNull() {
        assertNull(mapGeminiTools(emptyList()))
    }

    @Test
    fun extractChunk_textAndFunctionCall() {
        val chunk = ProviderJson.decodeFromString(
            GeminiStreamChunkDto.serializer(),
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Секунду"},{"functionCall":{"name":"device_state","args":{"detailed":true}}}]} ,"finishReason":"STOP"}]}""",
        )
        val extracted = extractGeminiChunk(chunk, nextId = 0)
        assertEquals(2, extracted.chunks.size)
        assertEquals(ChatChunk.Content("Секунду"), extracted.chunks[0])
        val call = extracted.chunks[1] as ChatChunk.ToolCall
        assertEquals("call_0", call.id)
        assertEquals("device_state", call.name)
        assertEquals(true, ProviderJson.parseToJsonElement(call.argumentsJson).jsonObject["detailed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("STOP", extracted.finishReason)
        assertEquals(1, extracted.nextId)
    }

    @Test
    fun extractChunk_emptyParts_yieldsNothing() {
        val chunk = ProviderJson.decodeFromString(
            GeminiStreamChunkDto.serializer(),
            """{"candidates":[{"content":{"role":"model","parts":[]}}]}""",
        )
        val extracted = extractGeminiChunk(chunk, nextId = 3)
        assertEquals(emptyList<ChatChunk>(), extracted.chunks)
        assertEquals(null, extracted.finishReason)
        assertEquals(3, extracted.nextId)
    }

    @Test
    fun secondCall_continuesIdCounter() {
        val chunk = ProviderJson.decodeFromString(
            GeminiStreamChunkDto.serializer(),
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"a","args":{}}},{"functionCall":{"name":"b"}}]}}]}""",
        )
        val extracted = extractGeminiChunk(chunk, nextId = 1)
        assertEquals(
            listOf("call_1", "call_2"),
            extracted.chunks.map { (it as ChatChunk.ToolCall).id },
        )
        assertEquals(
            listOf("a", "b"),
            extracted.chunks.map { (it as ChatChunk.ToolCall).name },
        )
        assertEquals(3, extracted.nextId)
    }

    @Test
    fun missingArgs_defaultToEmptyObject() {
        val chunk = ProviderJson.decodeFromString(
            GeminiStreamChunkDto.serializer(),
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"b"}}]}}]}""",
        )
        val call = extractGeminiChunk(chunk, nextId = 0).chunks.single() as ChatChunk.ToolCall
        assertEquals("{}", call.argumentsJson)
    }
}
