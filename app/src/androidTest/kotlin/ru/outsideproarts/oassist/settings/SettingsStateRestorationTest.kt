package ru.outsideproarts.oassist.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.feature.settings.ModeConfigScreen
import ru.outsideproarts.oassist.feature.settings.ProviderEditScreen
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

/**
 * Пересоздание Activity (поворот, смена темы, смерть процесса) не должно
 * терять пользовательский ввод форм настроек. Секреты ввода намеренно не
 * сохраняются в savedInstanceState и после восстановления пустеют.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStateRestorationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var base: android.content.Context

    @Before fun prepare() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test fun newProviderFormSurvivesRestoration() {
        val restoration = showProviderEditor(profile = null)
        compose.onNodeWithText(base.getString(SettingsR.string.provider_name))
            .performScrollTo()
            .performTextInput("Restored Provider")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Restored Provider").assertExists()
    }

    @Test fun newProviderSecretInputIsNotRestored() {
        val restoration = showProviderEditor(profile = null)
        compose.onNodeWithText(base.getString(SettingsR.string.provider_name))
            .performScrollTo()
            .performTextInput("Any Name")
        compose.onNodeWithText(base.getString(SettingsR.string.provider_api_key))
            .performScrollTo()
            .performTextInput("top-secret-key")
        restoration.emulateSavedInstanceStateRestore()
        // Поле имени сохранено, секрет остался только в памяти и не пишется в Bundle.
        compose.onNodeWithText("Any Name").assertExists()
        assertTrue(compose.onAllNodesWithText("top-secret-key").fetchSemanticsNodes().isEmpty())
    }

    @Test fun editedProviderInputSurvivesResyncFromProfile() {
        val profile = ProviderProfile(
            id = "p-test",
            name = "Old Name",
            preset = ProviderPreset.OPENAI,
            baseUrl = "https://example.test/v1",
        )
        val restoration = showProviderEditor(profile = profile)
        compose.onNodeWithText("Old Name")
            .performScrollTo()
            .performTextReplacement("Edited Name")
        restoration.emulateSavedInstanceStateRestore()
        // Ресинк из профиля не должен затирать восстановленный ввод.
        compose.onNodeWithText("Edited Name").assertExists()
    }

    @Test fun modeConfigPromptDraftSurvivesRestoration() {
        val restoration = StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                MaterialTheme {
                    ModeConfigScreen(
                        mode = AssistMode.CHAT,
                        config = null,
                        providers = emptyList(),
                        models = emptyList(),
                        isLoadingModels = false,
                        onSave = {},
                        onRefreshModels = {},
                        onProviderSelected = {},
                        isProbing = false,
                        onProbeModel = { _, _ -> },
                        onSaveCapabilities = { _, _, _ -> },
                        onBack = {},
                    )
                }
            }
        }
        // На «Начальный промпт» совпадают и заголовок секции, и label поля:
        // берём узел с текстовым вводом.
        compose.onNode(hasSetTextAction() and hasText(base.getString(SettingsR.string.mode_prompt)))
            .performScrollTo()
            .performTextInput("My draft prompt")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("My draft prompt").assertExists()
    }

    private fun showProviderEditor(profile: ProviderProfile?): StateRestorationTester =
        StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                MaterialTheme {
                    ProviderEditScreen(
                        profile = profile,
                        isSaving = false,
                        saveError = null,
                        onSave = { _, _ -> },
                        onDismissSaveError = {},
                        onBack = {},
                    )
                }
            }
        }
}
