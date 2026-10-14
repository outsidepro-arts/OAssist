package ru.outsideproarts.oassist.core.provider.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelProtocol

class ProtocolResolutionTest {

    @Test
    fun npmToProtocol_mapsKnownSdkPackages() {
        assertEquals(ModelProtocol.OPENAI_RESPONSES, ProtocolResolution.npmToProtocol("@ai-sdk/openai"))
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, ProtocolResolution.npmToProtocol("@ai-sdk/anthropic"))
        assertEquals(ModelProtocol.GEMINI_GENERATE, ProtocolResolution.npmToProtocol("@ai-sdk/google"))
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.npmToProtocol("@ai-sdk/openai-compatible"))
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.npmToProtocol("@ai-sdk/mistral"))
        assertNull(ProtocolResolution.npmToProtocol("@ai-sdk/unknown"))
    }

    @Test
    fun heuristic_routesUnambiguousFamilies() {
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, ProtocolResolution.heuristic("claude-opus-4-8"))
        assertEquals(ModelProtocol.GEMINI_GENERATE, ProtocolResolution.heuristic("gemini-3.5-flash"))
        assertEquals(ModelProtocol.OPENAI_RESPONSES, ProtocolResolution.heuristic("gpt-5.5"))
        assertEquals(ModelProtocol.OPENAI_RESPONSES, ProtocolResolution.heuristic("grok-4.7"))
        assertEquals(ModelProtocol.OPENAI_RESPONSES, ProtocolResolution.heuristic("muse-spark-1.3"))
    }

    @Test
    fun heuristic_defaultsToChatCompletions() {
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.heuristic("deepseek-v4-flash"))
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.heuristic("glm-5.3"))
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.heuristic("qwen3.8-max"))
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.heuristic("minimax-m3"))
        assertEquals(ModelProtocol.OPENAI_COMPLETIONS, ProtocolResolution.heuristic("mimo-v2.5-free"))
    }

    @Test
    fun resolve_chainOverrideBeatsCatalogBeatsHeuristic() {
        assertEquals(
            ModelProtocol.OPENAI_COMPLETIONS,
            ProtocolResolution.resolve("claude-opus-4-8", ModelProtocol.OPENAI_COMPLETIONS, "@ai-sdk/anthropic"),
        )
        assertEquals(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ProtocolResolution.resolve("claude-opus-4-8", null, "@ai-sdk/anthropic"),
        )
        assertEquals(
            ModelProtocol.OPENAI_COMPLETIONS,
            ProtocolResolution.resolve("qwen3.8-max", null, "@ai-sdk/openai-compatible"),
        )
        assertEquals(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ProtocolResolution.resolve("claude-opus-4-8", null, null),
        )
        assertEquals(
            ModelProtocol.OPENAI_COMPLETIONS,
            ProtocolResolution.resolve("some-unknown-model", null, null),
        )
    }

    @Test
    fun resolve_unknownCatalogNpm_fallsThroughToHeuristic() {
        assertEquals(
            ModelProtocol.GEMINI_GENERATE,
            ProtocolResolution.resolve("gemini-3-flash", null, "@ai-sdk/whatever"),
        )
    }
}
