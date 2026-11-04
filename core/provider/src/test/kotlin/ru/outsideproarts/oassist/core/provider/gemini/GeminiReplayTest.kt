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
