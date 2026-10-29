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
import ru.outsideproarts.oassist.core.model.ModelProtocol
import ru.outsideproarts.oassist.core.model.AuthScheme
import ru.outsideproarts.oassist.core.model.ModelFetchScheme
import ru.outsideproarts.oassist.core.model.ProviderPreset
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
            preset = ProviderPreset.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            protocol = ModelProtocol.OPENAI_RESPONSES,
            fetchScheme = ModelFetchScheme.MODELS,
            authScheme = AuthScheme.API_KEY_HEADER,
            apiKeyHeaderName = "x-key",
            basicUserRef = "provider.p1.basicUser",
            basicPasswordRef = "provider.p1.basicPassword",
            apiKeyRef = "provider.p1.apiKey",
            extraHeaders = mapOf("X-Org" to "provider.p1.header.x-org"),
            enabled = true,
        )

        val decoded = json.decodeFromString<ProviderProfile>(json.encodeToString(profile))

        assertEquals(profile, decoded)
    }

    @Test
    fun providerProfile_newFormatWritesPresetNotLegacyKind() {
        val profile = ProviderProfile(
            id = "p1",
            name = "P",
            preset = ProviderPreset.CUSTOM,
            baseUrl = "https://x",
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            fetchScheme = ModelFetchScheme.MODELS,
            authScheme = AuthScheme.NATIVE,
        )

        val encoded = json.encodeToString(profile)

        assertEquals(true, encoded.contains("\"preset\""))
        assertEquals(true, encoded.contains("\"ANTHROPIC_MESSAGES\""))
        assertEquals(false, encoded.contains("\"kind\""))
    }

    @Test
    fun providerProfile_legacyBasicRefs_ignored() {
        // Профили версий с Basic Auth (до удаления): неизвестные ключи
        // игнорируются, остальное читается как обычно. Легаси-ссылки Basic
        // осознанно не маппятся (поля не выходили за пределы ранних тестов).
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
        assertEquals(ProviderPreset.OPENCODE, decoded.preset)
        assertEquals(null, decoded.protocol)
        assertEquals(ModelFetchScheme.MODELS, decoded.fetchScheme)
        assertEquals(AuthScheme.NATIVE, decoded.authScheme)
        assertEquals("https://opencode.ai/zen/v1", decoded.baseUrl)
        assertEquals(null, decoded.basicUserRef)
        assertEquals(null, decoded.basicPasswordRef)
    }

    @Test
    fun providerProfile_legacyOpenCodeKinds_migrateToOpenCode() {
        val legacyKinds = listOf("OPENCODE", "OPENCODE_ZEN")
        for (legacy in legacyKinds) {
            val legacyJson = """
                {
                  "id": "p1",
                  "name": "Zen",
                  "kind": "$legacy",
                  "baseUrl": "https://opencode.ai/zen/go/v1"
                }
            """.trimIndent()

            val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

            assertEquals("kind=$legacy", ProviderPreset.OPENCODE, decoded.preset)
            assertEquals("kind=$legacy", null, decoded.protocol)
        }
    }

    @Test
    fun providerProfile_legacySimpleKinds_migrateToCustomSetup() {
        // Миграция сохраняет поведение: фикс протокола бывшего kind, каталог —
        // единый {base}/models, авторизация — прежняя нативная; версия API
        // материализуется в baseUrl (легаси-пути вставляли её сами).
        data class LegacyCase(
            val kind: String,
            val protocol: ModelProtocol,
            val expectedBaseUrl: String,
        )
        val cases = listOf(
            LegacyCase("OPENAI_COMPAT", ModelProtocol.OPENAI_COMPLETIONS, "https://example.com"),
            LegacyCase("OPENAI_RESPONSES", ModelProtocol.OPENAI_RESPONSES, "https://example.com"),
            LegacyCase("ANTHROPIC", ModelProtocol.ANTHROPIC_MESSAGES, "https://example.com/v1"),
            LegacyCase("GEMINI", ModelProtocol.GEMINI_GENERATE, "https://example.com/v1beta"),
        )
        for (case in cases) {
            val legacyJson = """
                {
                  "id": "p1",
                  "name": "P",
                  "kind": "${case.kind}",
                  "baseUrl": "https://example.com"
                }
            """.trimIndent()

            val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

            assertEquals("kind=${case.kind}", ProviderPreset.CUSTOM, decoded.preset)
            assertEquals("kind=${case.kind}", case.protocol, decoded.protocol)
            assertEquals("kind=${case.kind}", ModelFetchScheme.MODELS, decoded.fetchScheme)
            assertEquals("kind=${case.kind}", AuthScheme.NATIVE, decoded.authScheme)
            assertEquals("kind=${case.kind}", case.expectedBaseUrl, decoded.baseUrl)
        }
    }

    @Test
    fun providerProfile_legacyKinds_keepVersionAlreadyInBaseUrl() {
        for ((kind, base) in listOf(
            "ANTHROPIC" to "https://api.anthropic.com/v1",
            "GEMINI" to "https://generativelanguage.googleapis.com/v1beta",
        )) {
            val legacyJson = """{"id":"p1","name":"P","kind":"$kind","baseUrl":"$base"}"""

            val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

            assertEquals("kind=$kind", base, decoded.baseUrl)
        }
    }

    @Test
    fun providerProfile_legacyKinds_matchKnownHostsToPresets() {
        data class PresetCase(
            val kind: String,
            val baseUrl: String,
            val preset: ProviderPreset,
            val expectedBaseUrl: String,
        )
        val cases = listOf(
            PresetCase("OPENAI_COMPAT", "https://api.openai.com/v1", ProviderPreset.OPENAI, "https://api.openai.com/v1"),
            // Host-only base давал нерабочие пути — конфиг чинится в пресет.
            PresetCase("OPENAI_COMPAT", "https://api.openai.com/", ProviderPreset.OPENAI, "https://api.openai.com/v1"),
            PresetCase("OPENAI_RESPONSES", "https://api.openai.com/v1", ProviderPreset.OPENAI, "https://api.openai.com/v1"),
            PresetCase("ANTHROPIC", "https://api.anthropic.com", ProviderPreset.ANTHROPIC, "https://api.anthropic.com/v1"),
            PresetCase("GEMINI", "https://generativelanguage.googleapis.com", ProviderPreset.GOOGLE, "https://generativelanguage.googleapis.com/v1beta"),
            PresetCase("GEMINI", "https://generativelanguage.googleapis.com/v1beta", ProviderPreset.GOOGLE, "https://generativelanguage.googleapis.com/v1beta"),
            PresetCase("GEMINI", "https://generativelanguage.googleapis.com/v1", ProviderPreset.GOOGLE, "https://generativelanguage.googleapis.com/v1"),
            PresetCase("OPENCODE_ZEN", "https://opencode.ai/zen/v1", ProviderPreset.OPENCODE, "https://opencode.ai/zen/v1"),
            PresetCase("OPENCODE", "https://opencode.ai/zen/go/v1/", ProviderPreset.OPENCODE, "https://opencode.ai/zen/go/v1"),
        )
        for (case in cases) {
            val legacyJson = """{"id":"p1","name":"P","kind":"${case.kind}","baseUrl":"${case.baseUrl}"}"""

            val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

            assertEquals("${case.kind} ${case.baseUrl}", case.preset, decoded.preset)
            assertEquals("${case.kind} ${case.baseUrl}", case.expectedBaseUrl, decoded.baseUrl)
        }
    }

    @Test
    fun providerProfile_legacyKinds_nonStandardBaseStayCustomSetup() {
        data class CustomCase(val kind: String, val expectedBaseUrl: String)
        val cases = listOf(
            CustomCase("OPENAI_COMPAT", "https://proxy.example.com/api/v1"),
            CustomCase("ANTHROPIC", "https://proxy.example.com/api/v1"),
            CustomCase("GEMINI", "https://proxy.example.com/api/v1/v1beta"),
            CustomCase("OPENCODE_ZEN", "https://proxy.example.com/api/v1"),
        )
        for (case in cases) {
            val legacyJson = """{"id":"p1","name":"P","kind":"${case.kind}","baseUrl":"https://proxy.example.com/api/v1"}"""

            val decoded = json.decodeFromString<ProviderProfile>(legacyJson)

            assertEquals("kind=${case.kind}", ProviderPreset.CUSTOM, decoded.preset)
            assertEquals("kind=${case.kind}", case.expectedBaseUrl, decoded.baseUrl)
        }
    }

    @Test
    fun providerProfile_extraBodyAndCatalogPath_roundTrip() {
        val profile = ProviderProfile(
            id = "p1",
            name = "P",
            preset = ProviderPreset.CUSTOM,
            baseUrl = "https://proxy.example.com/v1",
            catalogPath = "catalog",
            extraBody = """{"provider":{"order":["x"]}}""",
        )

        val decoded = json.decodeFromString<ProviderProfile>(json.encodeToString(profile))

        assertEquals(profile, decoded)
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun providerProfile_unknownLegacyKind_rejected() {
        json.decodeFromString<ProviderProfile>(
            """{"id":"p1","name":"P","kind":"SOMETHING_NEW","baseUrl":"https://x"}""",
        )
    }

    @Test
    fun providerProfile_openCodeFlavor_roundTrips() {
        val profile = ProviderProfile(
            id = "p1",
            name = "Zen Go",
            preset = ProviderPreset.OPENCODE,
            baseUrl = "https://opencode.ai/zen/go/v1",
        )

        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString<ProviderProfile>(encoded)

        assertEquals(ProviderPreset.OPENCODE, decoded.preset)
        assertEquals(true, encoded.contains("\"OPENCODE\""))
        assertEquals(false, encoded.contains("OPENCODE_ZEN"))
    }

    @Test
    fun providerProfile_neverStoresSecretValues() {
        val profile = ProviderProfile(
            id = "p1",
            name = "Test",
            preset = ProviderPreset.CUSTOM,
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.com",
            apiKeyRef = "provider.p1.apiKey",
            basicUserRef = "provider.p1.basicUser",
            basicPasswordRef = "provider.p1.basicPassword",
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
                voiceLocale = "ru-RU",
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
    fun modeConfig_legacyTtsWithoutVoiceLocale_decodesWithNull() {
        // Конфиги до поля voiceLocale: отсутствующее поле — null («язык не
        // выбран»), остальное читается как обычно.
        val legacyJson = """
            {
              "mode": "CHAT",
              "tts": {
                "channel": "SYSTEM_TTS",
                "enginePackage": "com.google.android.tts",
                "voiceName": "ru-RU-TestVoice"
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ModeConfig>(legacyJson)

        assertEquals("ru-RU-TestVoice", decoded.tts?.voiceName)
        assertEquals(null, decoded.tts?.voiceLocale)
    }

    @Test
    fun modeConfig_stt_roundTripAndLegacyDefault() {
        val config = ModeConfig(
            mode = AssistMode.CHAT,
            stt = SttConfig(
                engine = SttEngine.MODEL_AUDIO,
                language = "en-US",
                recognizerComponent = "org.example.speech/org.example.speech.CustomRecognitionService",
                bluetoothInput = false,
            ),
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)

        val legacy = json.decodeFromString<ModeConfig>("""{"mode":"CHAT"}""")
        assertEquals(null, legacy.stt)
        assertEquals(SttConfig.DEFAULT, legacy.effectiveStt())
    }

    @Test
    fun modeConfig_legacySttWithoutRecognizer_usesSystemDefault() {
        val legacy = json.decodeFromString<ModeConfig>(
            """{"mode":"ASSISTANT","stt":{"engine":"SYSTEM","language":"ru-RU"}}""",
        )

        assertEquals(SttEngine.SYSTEM, legacy.effectiveStt().engine)
        assertEquals("ru-RU", legacy.effectiveStt().language)
        assertEquals(null, legacy.effectiveStt().recognizerComponent)
    }

    @Test
    fun modeConfig_legacySttWithoutBluetooth_usesAutoRoute() {
        val legacy = json.decodeFromString<ModeConfig>(
            """{"mode":"CHAT","stt":{"engine":"MODEL_AUDIO","language":"ru-RU"}}""",
        )

        assertEquals(true, legacy.effectiveStt().bluetoothInput)

        val explicit = json.decodeFromString<ModeConfig>(
            """{"mode":"CHAT","stt":{"engine":"SYSTEM","language":"ru-RU","bluetoothInput":false}}""",
        )

        assertEquals(false, explicit.effectiveStt().bluetoothInput)
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
    fun modeConfig_soundEnabled_roundTripAndLegacyDefault() {
        val config = ModeConfig(
            mode = AssistMode.CHAT,
            soundEnabled = false,
        )

        val decoded = json.decodeFromString<ModeConfig>(json.encodeToString(config))

        assertEquals(config, decoded)
        assertEquals(false, decoded.effectiveSoundEnabled())

        val legacy = json.decodeFromString<ModeConfig>("""{"mode":"CHAT"}""")
        assertEquals(null, legacy.soundEnabled)
        assertEquals(true, legacy.effectiveSoundEnabled())
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
    fun modelInfo_protocolOverride_roundTrips() {
        val info = ModelInfo(id = "claude-opus-4-8", protocol = ModelProtocol.ANTHROPIC_MESSAGES)

        val decoded = json.decodeFromString<ModelInfo>(json.encodeToString(info))

        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, decoded.protocol)
    }

    @Test
    fun modelInfo_legacyJsonWithoutProtocol_readsNull() {
        val legacy = json.decodeFromString<ModelInfo>("""{"id":"gpt-4o","label":"GPT-4o"}""")

        assertEquals(null, legacy.protocol)
    }

    @Test
    fun modeConfig_default_hasExtrasPerMode() {
        assertEquals(null, ModeConfig.default(AssistMode.CHAT).assistant)
        assertEquals(AssistantExtras(), ModeConfig.default(AssistMode.ASSISTANT).assistant)
        assertEquals(ShareExtras(), ModeConfig.default(AssistMode.SHARE).share)
    }
}
