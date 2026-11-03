package ru.outsideproarts.oassist.share

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.feature.share.ShareActivity
import ru.outsideproarts.oassist.feature.share.R as ShareR

/**
 * Пересоздание Activity не должно обнулять сессию шаринга: состояние живёт в
 * ShareSessionViewModel, поэтому открытый разговор и черновик вопроса
 * переживают recreate() (поворот, смена языка/темы).
 */
@RunWith(AndroidJUnit4::class)
class ShareSessionPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<ShareActivity>()

    @Test fun openConversationAndDraftSurviveRecreation() {
        assertConversationAndDraftSurviveRecreation()
    }

    @Test fun openConversationAndDraftSurviveRecreationWithDifferentAppLanguage() {
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val language = if (context.resources.configuration.locales[0].language == "ru") "en" else "ru"
        try {
            compose.runOnUiThread {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.locales[0].language == language
            }
            assertConversationAndDraftSurviveRecreation()
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(originalLocales) }
        }
    }

    private fun assertConversationAndDraftSurviveRecreation() {
        // On API 31/32, AppCompat localizes the Activity separately from targetContext.
        val context = compose.activity
        val newChatLabel = context.getString(
            ru.outsideproarts.oassist.core.ui.R.string.chats_new,
        )
        compose.onNodeWithText(newChatLabel).performClick()
        compose.onNodeWithText(context.getString(ShareR.string.share_ask_label))
            .performTextInput("draft question")
        compose.activityRule.scenario.recreate()
        // Разговор остался открытым, черновик на месте.
        compose.onNodeWithText("draft question").assertExists()
    }
}
