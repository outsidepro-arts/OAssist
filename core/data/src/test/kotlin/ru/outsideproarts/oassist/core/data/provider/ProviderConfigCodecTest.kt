package ru.outsideproarts.oassist.core.data.provider

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.AssistantExtras
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.ShareExtras
import ru.outsideproarts.oassist.core.model.SpeechOutputChannel
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.model.SttEngine
import ru.outsideproarts.oassist.core.model.TtsConfig

class ProviderConfigCodecTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun providerProfile_roundTrip() {
        val profile = ProviderProfile(
            id = "p1",
            name = "My OpenAI",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.example.com/v1",
            apiKeyRef = "provider.p1.apiKey",
            extraHeaders = mapOf("X-Org" to "provider.p1.header.x-org"),
            enabled = true,
        )

        val decoded = json.decodeFromString<ProviderProfile>(json.encodeToString(profile))

        assertEquals(profile, decoded)
    }

    @Test
    fun providerProfile_legacyBasicRefs_ignored() {
        // Профили версий с Basic Auth (до удаления): неизвестные ключи
        // игнорируются, остальное читается как обычно.
        val legacyJson = """
            {
              "id": "p1",
              "name": "Zen",
              "kind": "OPENCODE_ZEN",
              "baseUrl": "https://opencode.ai/zen/v1",
              "basicAuthUserRef": "provider.p1.basicUser",
              "basicAuthPasswordRef": "provider.p1.basicPassword"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

        assertEquals("p1", decoded.id)
        assertEquals(ProviderKind.OPENCODE_ZEN, decoded.kind)
    }

    @Test
    fun providerProfile_legacyOpenCodeKind_migratesToOpenCodeZen() {
        val legacyJson = """
            {
              "id": "p1",
              "name": "Zen",
              "kind": "OPENCODE",
              "baseUrl": "https://opencode.ai/zen/go/v1"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

        assertEquals(ProviderKind.OPENCODE_ZEN, decoded.kind)
    }

    @Test
    fun providerProfile_openCodeZenKind_roundTrips() {
        val profile = ProviderProfile(
            id = "p1",
            name = "Zen Go",
            kind = ProviderKind.OPENCODE_ZEN,
            baseUrl = "https://opencode.ai/zen/go/v1",
        )

        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString<ProviderProfile>(encoded)

        assertEquals(ProviderKind.OPENCODE_ZEN, decoded.kind)
        assertEquals(true, encoded.contains("OPENCODE_ZEN"))
        assertEquals(false, encoded.contains("\"OPENCODE\""))
    }

    @Test
    fun providerProfile_neverStoresSecretValues() {
        val profile = ProviderProfile(
            id = "p1",
            name = "Test",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            apiKeyRef = "provider.p1.apiKey",
        )

        val encoded = json.encodeToString(profile)

        assertEquals(false, encoded.contains("sk-"))
        assertEquals(true, encoded.contains("provider.p1.apiKey"))
    }

    @Test
    fun modeConfig_roundTrip() {
        val config = ModeConfig(
            mode = AssistMode.ASSISTANT,
            providerId = "p1",
            modelId = "gpt-4o",
            initialPrompt = "Ты ассистент",
            assistant = AssistantExtras(autoSpeak = false, autoClose = false, attachScreenshot = true),
            tts = TtsConfig(
                channel = SpeechOutputChannel.SCREEN_READER,
                rate = 1.2f,
                pitch = 0.9f,
                volume = 0.8f,
                enginePackage = "com.google.android.tts",
                voiceName = "ru-RU-TestVoice",
                speakToolCalls = false,
                narrative = false,
            ),
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)
    }

    @Test
    fun modeConfig_legacyWithoutTts_decodesWithNull() {        val legacyJson = """
            {
              "mode": "CHAT",
              "providerId": "p1",
              "modelId": "m1"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ModeConfig>(legacyJson)

        assertEquals(null, decoded.tts)
        assertEquals(TtsConfig.DEFAULT, decoded.effectiveTts())
    }

    @Test
    fun modeConfig_stt_roundTripAndLegacyDefault() {
        val config = ModeConfig(
            mode = AssistMode.CHAT,
            stt = SttConfig(engine = SttEngine.MODEL_AUDIO, language = "en-US"),
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)

        val legacy = json.decodeFromString<ModeConfig>("""{"mode":"CHAT"}""")
        assertEquals(null, legacy.stt)
        assertEquals(SttConfig.DEFAULT, legacy.effectiveStt())
    }

    @Test
    fun modeConfig_shareExtras_roundTrip() {
        val config = ModeConfig(
            mode = AssistMode.SHARE,
            providerId = "p2",
            modelId = "model-x",
            share = ShareExtras(sharedContentAction = "Сделай саммари", showInChat = false),
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)
    }

    @Test
    fun modeConfig_continueDialog_roundTripAndLegacyDefault() {
        val config = ModeConfig(
            mode = AssistMode.ASSISTANT,
            assistant = AssistantExtras(continueDialog = true),
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)

        val legacy = json.decodeFromString<ModeConfig>(
            """{"mode":"ASSISTANT","assistant":{"autoSpeak":false,"autoClose":true,"attachScreenshot":true}}""",
        )
        assertEquals(false, legacy.assistant?.continueDialog)
    }

    @Test
    fun modeConfig_showOverlay_roundTripAndLegacyDefault() {
        val config = ModeConfig(
            mode = AssistMode.ASSISTANT,
            assistant = AssistantExtras(showOverlay = false),
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)
        assertEquals(false, decoded.assistant?.showOverlay)

        val legacy = json.decodeFromString<ModeConfig>(
            """{"mode":"ASSISTANT","assistant":{"autoSpeak":false,"autoClose":true,"attachScreenshot":true}}""",
        )
        assertEquals(true, legacy.assistant?.showOverlay)
    }

    @Test
    fun modelCatalog_roundTrip() {
        val catalog = ModelCatalog(
            providerId = "p1",
            updatedAtEpochMs = 123456789L,
            models = listOf(
                ModelInfo(id = "gpt-4o", label = "GPT-4o", capabilities = setOf(ModelCapability.VISION, ModelCapability.TOOLS)),
                ModelInfo(id = "gpt-4o-mini"),
            ),
        )

        val decoded = json.decodeFromString<ModelCatalog>(json.encodeToString(catalog))

        assertEquals(catalog, decoded)
    }

    @Test
    fun modeConfig_default_hasExtrasPerMode() {
        assertEquals(null, ModeConfig.default(AssistMode.CHAT).assistant)
        assertEquals(AssistantExtras(), ModeConfig.default(AssistMode.ASSISTANT).assistant)
        assertEquals(ShareExtras(), ModeConfig.default(AssistMode.SHARE).share)
    }
}
