package ru.outsideproarts.oassist.settings

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.MainActivity
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

@RunWith(AndroidJUnit4::class)
class UpdateNotificationLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun notificationCanOpenUpdatesBeforeNavigationGraphIsCreated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(SettingsR.string.update_title)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("open_updates", true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).assertIsDisplayed()
        }
    }
}
