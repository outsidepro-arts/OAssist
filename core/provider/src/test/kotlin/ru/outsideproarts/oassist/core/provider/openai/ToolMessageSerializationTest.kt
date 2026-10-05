package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.ProviderJson

class ToolMessageSerializationTest {

    @Test
    fun toolPair_serializesWithIds() {
        val request = ChatRequestDto(
            model = "m",
            messages = listOf(
                ChatMessageDto(role = "user", content = JsonPrimitive("выключи фонарик")),
                ChatMessageDto(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCallRequestDto(
                            id = "call_1",
                            function = ToolCallRequestFunctionDto(
                                name = "flashlight",
                                arguments = "{\"on\":false}",
                            ),
                        ),
                    ),
                ),
                ChatMessageDto(role = "tool", content = JsonPrimitive("Фонарик выключен"), toolCallId = "call_1"),
            ),
            stream = true,
            tools = null,
        )

        val json = ProviderJson.encodeToString(ChatRequestDto.serializer(), request)

        assertTrue(json.contains("\"tool_calls\""))
        assertTrue(json.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(json.contains("\"role\":\"tool\""))
        assertTrue(json.contains("\"id\":\"call_1\""))
    }

    @Test
    fun noLegacyToolTextMarker() {
        // Старый формат "[tool:name] ..." в историю больше не кладётся.
        val request = ChatRequestDto(
            model = "m",
            messages = listOf(ChatMessageDto(role = "tool", content = JsonPrimitive("ok"), toolCallId = "c1")),
            stream = true,
            tools = null,
        )
        val json = ProviderJson.encodeToString(ChatRequestDto.serializer(), request)
        assertFalse(json.contains("[tool:"))
    }
}
