package ru.outsideproarts.oassist.share

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
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
    private lateinit var base: android.content.Context

    @Before fun prepare() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test fun openConversationAndDraftSurviveRecreation() {
        val newChatLabel = base.getString(
            ru.outsideproarts.oassist.core.ui.R.string.chats_new,
        )
        compose.onNodeWithText(newChatLabel).performClick()
        compose.onNodeWithText(base.getString(ShareR.string.share_ask_label))
            .performTextInput("draft question")
        compose.activityRule.scenario.recreate()
        // Разговор остался открытым, черновик на месте.
        compose.onNodeWithText("draft question").assertExists()
    }
}
