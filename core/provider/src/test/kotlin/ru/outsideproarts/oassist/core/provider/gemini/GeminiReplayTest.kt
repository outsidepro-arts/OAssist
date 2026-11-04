package ru.outsideproarts.oassist.core.provider.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.GeminiReplay
import ru.outsideproarts.oassist.core.model.ReasoningBlock
import ru.outsideproarts.oassist.core.model.ToolCallRef

class GeminiReplayTest {
    private val parts = """[{"role":"model","parts":[
        {"text":"thought","thought":true,"thoughtSignature":"thinking-signature"},
        {"text":"answer","thoughtSignature":"text-signature"},
        {"functionCall":{"name":"ping","args":{"x":1}},"thoughtSignature":"call-signature"}
    ]}]"""
    private fun message(json: String = parts) = ChatMessage(ChatRole.ASSISTANT, "answer",
        toolCalls = listOf(ToolCallRef("local-id", "ping", """{"x":1}""", "call-signature")),
        reasoning = ReasoningBlock(text = "thought", geminiReplay = GeminiReplay("origin", json)))

    @Test
    fun changedVisibleThoughtOrToolSnapshotCannotRestoreOldParts() {
        val original = message()
        val variants = listOf(
            original.copy(content = "edited"),
            original.copy(reasoning = original.reasoning!!.copy(text = "edited thought")),
            original.copy(toolCalls = listOf(original.toolCalls!!.single().copy(name = "other"))),
            original.copy(toolCalls = listOf(original.toolCalls!!.single().copy(argumentsJson = "{}"))),
            original.copy(toolCalls = emptyList()),
            original.copy(toolCalls = listOf(original.toolCalls!!.single().copy(thoughtSignature = "changed"))),
            message("not JSON"),
        )
        variants.forEach { variant ->
            val mapped = mapGeminiContents(listOf(variant), "origin").second.flatMap { it.parts }
            assertEquals(variant.content, mapped.first().text)
            assertTrue(mapped.all { it.thoughtSignature == null && !it.thought })
        }
    }

    @Test
    fun localToolIdRemapDoesNotDiscardOtherwiseMatchingReplay() {
        val original = message()
        val remapped = original.copy(toolCalls = listOf(original.toolCalls!!.single().copy(id = "another-local-id")))
        val mapped = mapGeminiContents(listOf(remapped), "origin").second.single().parts
        assertEquals(listOf("thinking-signature", "text-signature", "call-signature"), mapped.map { it.thoughtSignature })
    }

    @Test
    fun serverIdentityIsIndependentOfRuntimeRemapAndStaleReplayCannotRestoreIt() {
        val original = message(parts.replace("\"name\":\"ping\"", "\"id\":\"server-id\",\"name\":\"ping\""))
            .let { it.copy(toolCalls = listOf(it.toolCalls!!.single().copy(geminiFunctionCallId = "server-id"))) }
        val remapped = original.copy(toolCalls = listOf(original.toolCalls!!.single().copy(id = "remapped")))
        val reply = ChatMessage(ChatRole.TOOL, "result", toolCallId = "remapped")
        val mapped = mapGeminiContents(listOf(remapped, reply), "origin").second
        assertEquals("server-id", mapped.first().parts.last().functionCall!!.id)
        assertEquals("call-signature", mapped.first().parts.last().thoughtSignature)
        assertEquals("server-id", mapped.last().parts.single().functionResponse!!.id)
        for (newId in listOf("changed", null)) {
            val edited = remapped.copy(toolCalls = listOf(remapped.toolCalls!!.single().copy(geminiFunctionCallId = newId)))
            val changed = mapGeminiContents(listOf(edited, reply), "origin").second
            assertEquals(newId, changed.first().parts.last().functionCall!!.id)
            assertEquals(newId, changed.last().parts.single().functionResponse!!.id)
            assertTrue(changed.flatMap { it.parts }.all { it.thoughtSignature == null })
        }
    }

    @Test
    fun outOfOrderResultsUseCallIdentityInsteadOfNameOrPositionAndLegacyIdsStayLocal() {
        val calls = listOf(ToolCallRef("local-a", "ping", "{}", geminiFunctionCallId = "server-a"),
            ToolCallRef("local-b", "ping", "{}", geminiFunctionCallId = "server-b"),
            Json.decodeFromString<ToolCallRef>("""{"id":"legacy","name":"ping","argumentsJson":"{}"}"""))
        val mapped = mapGeminiContents(listOf(ChatMessage(ChatRole.ASSISTANT, "", toolCalls = calls),
            ChatMessage(ChatRole.TOOL, "b", toolCallId = "local-b"),
            ChatMessage(ChatRole.TOOL, "a", toolCallId = "local-a"),
            ChatMessage(ChatRole.TOOL, "old", toolCallId = "legacy"))).second
        assertEquals(listOf("server-b", "server-a", null), mapped.drop(1).map { it.parts.single().functionResponse!!.id })
        assertEquals(listOf("server-a", "server-b", null), mapped.first().parts.map { it.functionCall!!.id })
    }

    @Test
    fun metadataOnlyEmptyTextRemainsARealPartAndOldHistoryNeedsNoMigration() {
        val metadata = ReasoningBlock(geminiReplay = GeminiReplay("origin",
            """[{"role":"model","parts":[{"text":"","thoughtSignature":"empty-signature"}]}]"""))
        assertFalse(metadata.isEmpty())
        val mapped = mapGeminiContents(listOf(ChatMessage(ChatRole.ASSISTANT, "", reasoning = metadata)), "origin").second
        assertEquals("", mapped.single().parts.single().text)
        assertEquals("empty-signature", mapped.single().parts.single().thoughtSignature)
        val old = Json.decodeFromString<ChatMessage>("""{"role":"ASSISTANT","content":"old","reasoning":{"text":"old thought"}}""")
        assertNull(old.reasoning!!.geminiReplay)
        assertEquals("old", mapGeminiContents(listOf(old), "origin").second.single().parts.single().text)
    }
}
