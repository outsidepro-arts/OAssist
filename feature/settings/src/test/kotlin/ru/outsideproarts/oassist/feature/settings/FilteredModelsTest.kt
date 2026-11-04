package ru.outsideproarts.oassist.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo

class FilteredModelsTest {

    @Test
    fun nonChatModelsExcludedFromBlankAndSearchQueries() {
        val mixed = listOf(
            ModelInfo("same-classifier", purpose = "classification"),
            ModelInfo("same-embedding", supportedGenerationMethods = listOf("embedContent")),
            ModelInfo("same-empty", supportedGenerationMethods = emptyList()),
            ModelInfo("same-chat", purpose = "chat"),
            ModelInfo("same-unknown"),
            ModelInfo("same-future", purpose = "future"),
        )
        for (query in listOf("", "same")) {
            assertEquals(setOf("same-chat", "same-unknown", "same-future"), filteredModels(query, mixed).map { it.id }.toSet())
        }
    }

    private val models = listOf(
        ModelInfo(id = "openai/gpt-4o", label = "GPT-4o"),
        ModelInfo(id = "anthropic/claude-3-5-sonnet", label = "Claude 3.5 Sonnet"),
        ModelInfo(id = "llama-3-70b", label = null),
        ModelInfo(id = "vendor/model-x", label = "Deep Thought"),
    )

    @Test
    fun blankQuery_keepsAllModelsInSourceOrder() {
        assertEquals(models, filteredModels("", models))
        assertEquals(models, filteredModels("   ", models))
    }

    @Test
    fun query_matchesById() {
        val result = filteredModels("claude", models)
        assertEquals(listOf("anthropic/claude-3-5-sonnet"), result.map { it.id })
    }

    @Test
    fun query_matchesByLabel() {
        val result = filteredModels("thought", models)
        assertEquals(listOf("vendor/model-x"), result.map { it.id })
    }

    @Test
    fun query_matchesByIdWhenLabelAbsent() {
        val result = filteredModels("llama", models)
        assertEquals(listOf("llama-3-70b"), result.map { it.id })
    }

    @Test
    fun query_matchesBySubsequence() {
        val result = filteredModels("gpt4", models)
        assertEquals(listOf("openai/gpt-4o"), result.map { it.id })
    }

    @Test
    fun query_ranksSubstringMatchBeforeScattered() {
        val models = listOf(
            ModelInfo(id = "vendor/g-p-t-x", label = null),
            ModelInfo(id = "openai/gpt-4o", label = null),
        )
        val result = filteredModels("gpt", models)
        assertEquals(listOf("openai/gpt-4o", "vendor/g-p-t-x"), result.map { it.id })
    }

    @Test
    fun query_withoutMatches_returnsEmpty() {
        assertTrue(filteredModels("qwerty", models).isEmpty())
    }
}
