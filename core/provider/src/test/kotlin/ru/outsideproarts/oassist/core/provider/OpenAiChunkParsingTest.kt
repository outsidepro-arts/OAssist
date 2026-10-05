package ru.outsideproarts.oassist.core.provider

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.provider.openai.ChatResponseDto

class OpenAiChunkParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Минимальный парсер «как внутри sse-collect»: data → JSON → delta/finish. */
    private fun parseLine(data: String): List<ChatChunk> {
        if (data == "[DONE]") return listOf(ChatChunk.Finish(reason = null))
        val parsed = runCatching { json.decodeFromString<ChatResponseDto>(data) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ChatChunk>()
        val delta = parsed.choices.firstOrNull()?.delta?.content
        if (!delta.isNullOrEmpty()) out += ChatChunk.Content(delta)
        val finish = parsed.choices.firstOrNull()?.finishReason
        if (finish != null) out += ChatChunk.Finish(reason = finish)
        return out
    }

    @Test
    fun doneSentinel_yieldsFinish() {
        val r = parseLine("[DONE]")
        assertEquals(listOf(ChatChunk.Finish(reason = null)), r)
    }

    @Test
    fun deltaContent_yieldsContent() {
        val r = parseLine(
            """{"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":"Привет"},"finish_reason":null}]}""",
        )
        assertEquals(listOf(ChatChunk.Content("Привет")), r)
    }

    @Test
    fun finishReason_yieldsFinish() {
        val r = parseLine(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )
        assertEquals(listOf(ChatChunk.Finish(reason = "stop")), r)
    }

    @Test
    fun emptyDelta_isIgnored() {
        val r = parseLine(
            """{"choices":[{"index":0,"delta":{"content":""},"finish_reason":null}]}""",
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun malformedLine_isIgnored() {
        val r = parseLine("not-json-{")
        assertTrue(r.isEmpty())
    }

    @Test
    fun fullStream_assemblesContent() = runTest {
        val lines = listOf(
            """{"choices":[{"delta":{"content":"А,"}}]}""",
            """{"choices":[{"delta":{"content":"лиса"}}]}""",
            """{"choices":[{"delta":{"content":"!"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        val stream = flow {
            for (line in lines) for (chunk in parseLine(line)) emit(chunk)
        }
        val chunks = stream.toList()
        assertEquals(
            listOf(
                ChatChunk.Content("А,"),
                ChatChunk.Content("лиса"),
                ChatChunk.Content("!"),
                ChatChunk.Finish(reason = "stop"),
            ),
            chunks,
        )
    }
}