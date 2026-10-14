package ru.outsideproarts.oassist.core.data.provider

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol

class ModelCapabilitiesPatchTest {

    @Test
    fun existingModel_capsReplaced() {
        val current = ModelCatalog(
            providerId = "p1",
            updatedAtEpochMs = 1,
            models = listOf(
                ModelInfo(id = "a", capabilities = emptySet()),
                ModelInfo(id = "b", capabilities = setOf(ModelCapability.VISION)),
            ),
        )
        val patched = patchModelCapabilities(
            current, "p1", "a", setOf(ModelCapability.TOOLS), nowEpochMs = 2,
        )
        assertEquals(setOf(ModelCapability.TOOLS), patched.models.first { it.id == "a" }.capabilities)
        assertEquals(setOf(ModelCapability.VISION), patched.models.first { it.id == "b" }.capabilities)
        assertEquals(2, patched.updatedAtEpochMs)
    }

    @Test
    fun missingModel_appended() {
        val current = ModelCatalog(providerId = "p1", updatedAtEpochMs = 1, models = listOf(ModelInfo("a")))
        val patched = patchModelCapabilities(
            current, "p1", "manual-id", setOf(ModelCapability.REASONING), nowEpochMs = 5,
        )
        assertEquals(2, patched.models.size)
        assertEquals(
            setOf(ModelCapability.REASONING),
            patched.models.first { it.id == "manual-id" }.capabilities,
        )
    }

    @Test
    fun nullCatalog_created() {
        val patched = patchModelCapabilities(null, "p1", "m", emptySet(), nowEpochMs = 7)
        assertEquals("p1", patched.providerId)
        assertEquals(listOf("m"), patched.models.map { it.id })
    }

    @Test
    fun protocolOverride_patchedAndCleared() {
        val current = ModelCatalog(providerId = "p1", updatedAtEpochMs = 1, models = listOf(ModelInfo("a")))
        val withProtocol = patchModelProtocol(
            current, "p1", "a", ModelProtocol.ANTHROPIC_MESSAGES, nowEpochMs = 2,
        )
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, withProtocol.models.first().protocol)

        val cleared = patchModelProtocol(withProtocol, "p1", "a", null, nowEpochMs = 3)
        assertEquals(null, cleared.models.first().protocol)
    }

    @Test
    fun mergeCatalog_preservesUserEdits() {
        val current = ModelCatalog(
            providerId = "p1",
            updatedAtEpochMs = 1,
            models = listOf(
                ModelInfo(
                    id = "a",
                    label = "старое имя",
                    capabilities = setOf(ModelCapability.VISION),
                    protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                ),
            ),
        )
        val merged = mergeModelCatalog(
            current,
            "p1",
            listOf(ModelInfo(id = "a", label = "новое имя"), ModelInfo(id = "b")),
            nowEpochMs = 9,
        )

        val a = merged.models.first { it.id == "a" }
        assertEquals("новое имя", a.label)
        assertEquals(setOf(ModelCapability.VISION), a.capabilities)
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, a.protocol)
        assertEquals(listOf("a", "b"), merged.models.map { it.id })
    }
}
