package ru.outsideproarts.oassist.core.provider.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ToolCallRef
import ru.outsideproarts.oassist.core.provider.openai.ToolFunctionSchemaDto
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

class AnthropicMapperTest {

    @Test
    fun systemMessages_foldIntoSystemField() {
        val (system, messages) = mapAnthropicMessages(listOf(
            ChatMessage(ChatRole.SYSTEM, "Ты ассистент"),
            ChatMessage(ChatRole.USER, "Привет"),
        ))
        assertEquals("Ты ассистент", system)
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Привет", messages[0].content.jsonPrimitive.content)
    }

    @Test
    fun noSystem_yieldsNull() {
        val (system, _) = mapAnthropicMessages(listOf(ChatMessage(ChatRole.USER, "hi")))
        assertNull(system)
    }

    @Test
    fun assistantWithToolCalls_mapsToToolUseBlocks() {
        val (_, messages) = mapAnthropicMessages(listOf(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Смотрю",
                toolCalls = listOf(ToolCallRef("toolu_1", "device_state", "{\"detailed\":true}")),
            ),
        ))
        val content = messages.single().content.jsonArray
        assertEquals(2, content.size)
        assertEquals("Смотрю", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val toolUse = content[1].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("device_state", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals(true, toolUse["input"]!!.jsonObject["detailed"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun toolResult_mapsToUserRole() {
        val (_, messages) = mapAnthropicMessages(listOf(
            ChatMessage(ChatRole.TOOL, "torch=off", toolCallId = "toolu_1"),
        ))
        val msg = messages.single()
        assertEquals("user", msg.role)
        val block = msg.content.jsonArray.single().jsonObject
        assertEquals("tool_result", block["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", block["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("torch=off", block["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun tools_mapToInputSchema() {
        val tools = mapAnthropicTools(listOf(
            ToolSchemaDto(function = ToolFunctionSchemaDto(
                name = "device_state",
                description = "Снимок",
                parameters = buildJsonObject { put("type", "object") },
            )),
        ))
        val tool = tools!!.single()
        assertEquals("device_state", tool.name)
        assertEquals("object", tool.inputSchema.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyTools_mapToNull() {
        assertNull(mapAnthropicTools(emptyList()))
    }

    @Test
    fun nullParameters_mapToEmptySchema() {
        val tools = mapAnthropicTools(listOf(
            ToolSchemaDto(function = ToolFunctionSchemaDto(name = "x", parameters = null)),
        ))
        assertEquals(JsonObject(emptyMap()), tools!!.single().inputSchema)
    }

    @Test
    fun malformedArgumentsJson_fallsBackToEmptyObject() {
        val (_, messages) = mapAnthropicMessages(listOf(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCallRef("toolu_9", "x", "not-json-{")),
            ),
        ))
        val toolUse = messages.single().content.jsonArray.single().jsonObject
        assertEquals(JsonObject(emptyMap()), toolUse["input"]!!.jsonObject)
    }

    @Test
    fun plainText_staysPrimitive() {
        val (_, messages) = mapAnthropicMessages(listOf(
            ChatMessage(ChatRole.ASSISTANT, "Готово"),
        ))
        assertEquals("assistant", messages.single().role)
        assertEquals(JsonPrimitive("Готово"), messages.single().content)
    }
}
