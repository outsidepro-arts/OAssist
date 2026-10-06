package ru.outsideproarts.oassist.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.McpServerConfig
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile

class BackupCodecTest {

    private fun sample(): BackupV1 = BackupV1(
        exportedAtEpochMs = 1L,
        language = "RUSSIAN",
        providers = listOf(
            ProviderProfile(id = "p1", name = "P", kind = ProviderKind.OPENAI_COMPAT, baseUrl = "https://x"),
        ),
        modes = listOf(ModeConfig(mode = AssistMode.CHAT, providerId = "p1", modelId = "m")),
        catalogs = listOf(ModelCatalog(providerId = "p1", updatedAtEpochMs = 1L, models = listOf(ModelInfo("m")))),
        toolPrefs = ToolPrefsBackup(disabled = listOf("sms_send"), askOverrides = mapOf("ui_click" to true)),
        mcpServers = listOf(McpServerConfig(id = "s", name = "S", url = "https://mcp")),
        skills = listOf(SkillBackup(name = "tea", title = "Tea", body = "love tea", enabled = true)),
        memory = "люблю чай",
        alwaysAllowed = listOf("sms_send"),
    )

    @Test
    fun `round-trip сохраняет всё`() {
        val decoded = decodeBackup(encodeBackup(sample())).getOrThrow()
        assertEquals(sample().copy(exportedAtEpochMs = decoded.exportedAtEpochMs), decoded)
        assertEquals(listOf("sms_send"), decoded.toolPrefs.disabled)
        assertEquals("люблю чай", decoded.memory)
    }

    @Test
    fun `чужая версия отклоняется`() {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encodeBackup(sample()))
            .jsonObject.toMutableMap()
        obj["version"] = kotlinx.serialization.json.JsonPrimitive(99)
        val raw = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject(obj),
        )
        assertTrue(decodeBackup(raw).isFailure)
    }

    @Test
    fun `мусор отклоняется`() {
        assertTrue(decodeBackup("not json").isFailure)
        assertTrue(decodeBackup("").isFailure)
    }

    @Test
    fun `плохое имя навыка отклоняется`() {
        val bad = sample().copy(skills = listOf(SkillBackup(name = "Плохо!", title = "t", body = "b")))
        assertTrue(decodeBackup(encodeBackup(bad)).isFailure)
    }

    @Test
    fun `scrubbed режет секретные ссылки`() {
        val profile = ProviderProfile(
            id = "p", name = "P", kind = ProviderKind.ANTHROPIC, baseUrl = "https://x",
            apiKeyRef = "provider.p.apiKey", extraHeaders = mapOf("X-K" to "alias"),
        ).scrubbed()
        assertEquals(null, profile.apiKeyRef)
        assertTrue(profile.extraHeaders.isEmpty())
        val mcp = McpServerConfig(id = "s", name = "S", url = "u", bearerRef = "mcp.s.bearer").scrubbed()
        assertEquals(null, mcp.bearerRef)
    }

    @Test
    fun `scrubbed не светит секреты в JSON`() {
        val secret = ProviderProfile(
            id = "p", name = "P", kind = ProviderKind.ANTHROPIC, baseUrl = "https://x",
            apiKeyRef = "provider.p.apiKey",
        )
        val raw = encodeBackup(sample().copy(providers = listOf(secret.scrubbed())))
        assertFalse(raw.contains("provider.p.apiKey"))
    }
}
