package ru.outsideproarts.oassist.settings

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.MainActivity
import ru.outsideproarts.oassist.core.model.AppLanguage
import ru.outsideproarts.oassist.di.AppGraph
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

@RunWith(AndroidJUnit4::class)
class UpdateNotificationLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun notificationCanOpenUpdatesBeforeNavigationGraphIsCreated() {
        launchNotificationAndAssertUpdates()
    }

    @Test fun notificationSurvivesLanguageChangeOnColdLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = AppGraph.get().data.settings
        val originalLanguage = runBlocking { settings.language.first() }
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        try {
            runBlocking { settings.setLanguage(AppLanguage.RUSSIAN) }
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
            launchNotificationAndAssertUpdates(expectedLanguage = "ru")
        } finally {
            runBlocking { settings.setLanguage(originalLanguage) }
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(originalLocales) }
        }
    }

    private fun launchNotificationAndAssertUpdates(expectedLanguage: String? = null) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("open_updates", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var title = ""
            compose.waitUntil(10_000) {
                // MainActivity applies the app language and can recreate itself on launch.
                var languageApplied = false
                scenario.onActivity {
                    title = it.getString(SettingsR.string.update_title)
                    languageApplied = expectedLanguage == null ||
                        it.resources.configuration.locales[0].language == expectedLanguage
                }
                languageApplied && compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(title).assertIsDisplayed()
        }
    }
}
