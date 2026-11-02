package ru.outsideproarts.oassist.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.model.update.InstalledBuild
import ru.outsideproarts.oassist.core.model.update.UpdateCandidate
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import ru.outsideproarts.oassist.core.model.update.UpdateManifest
import ru.outsideproarts.oassist.core.model.update.UpdatePreferences
import ru.outsideproarts.oassist.feature.settings.R as SettingsR
import ru.outsideproarts.oassist.feature.settings.UpdatesScreen

@RunWith(AndroidJUnit4::class)
class UpdatesScreenTest {
    @get:Rule val compose = createComposeRule()

    private var preferences by mutableStateOf(UpdatePreferences())
    private var installed by mutableStateOf(InstalledBuild("app.example", 10, "1.2", false, "a".repeat(40), "b".repeat(64), 34, listOf("arm64-v8a")))
    private var candidate by mutableStateOf<UpdateCandidate?>(null)
    private var busy by mutableStateOf(false)
    private var downloading by mutableStateOf(false)
    private var checks = 0
    private var downloads = 0
    private var cancellations = 0
    private var selectedChannel: UpdateChannel? = null

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun showScreen() {
        compose.setContent {
            MaterialTheme {
                UpdatesScreen(
                    preferences = preferences, installed = installed, candidate = candidate,
                    status = "", busy = busy, downloading = downloading, progress = 42,
                    notificationsAllowed = true,
                    onAutomaticChange = { preferences = preferences.copy(automaticChecks = it) },
                    onNotificationsChange = { preferences = preferences.copy(notifications = it) },
                    onChannelChange = { selectedChannel = it; preferences = preferences.copy(manualChannel = it) },
                    onCheck = { checks++ }, onDownload = { downloads++ },
                    onCancelDownload = { cancellations++ }, onNotificationSettings = {}, onBack = {},
                )
            }
        }
    }

    @Test fun notificationsAppearOnlyWhenAutomaticChecksAreEnabled() {
        showScreen()
        val automatic = compose.onNodeWithText(text(SettingsR.string.update_automatic))
        val notifications = compose.onNodeWithText(text(SettingsR.string.update_notifications))
        automatic.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
        notifications.assertIsOff().performClick().assertIsOn()
        automatic.performClick()
        notifications.assertDoesNotExist()
        automatic.assertIsOff().performClick()
        notifications.assertIsOn()
    }

    @Test fun channelSelectionExposesRadioStateAndCallsSelection() {
        showScreen()
        val stable = compose.onNodeWithText(text(SettingsR.string.update_channel_stable_title))
        val prerelease = compose.onNodeWithText(text(SettingsR.string.update_channel_prerelease_title))
        stable.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
        prerelease.performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        stable.assertIsNotSelected()
        compose.runOnIdle { assertEquals(UpdateChannel.PRERELEASE, selectedChannel) }
        compose.onNodeWithText(text(SettingsR.string.update_channel_manual)).assertExists()
    }

    @Test fun automaticChannelReasonTracksInstalledBuild() {
        showScreen()
        compose.onNodeWithText(text(SettingsR.string.update_channel_stable)).assertExists()
        compose.runOnIdle { installed = installed.copy(versionName = "1.3.0-beta.1") }
        compose.onNodeWithText(text(SettingsR.string.update_channel_prerelease)).assertExists()
        compose.runOnIdle { installed = installed.copy(debug = true) }
        compose.onNodeWithText(text(SettingsR.string.update_channel_debug)).assertExists()
        compose.runOnIdle { preferences = preferences.copy(manualChannel = UpdateChannel.STABLE) }
        compose.onNodeWithText(text(SettingsR.string.update_channel_manual)).assertExists()
        compose.onNodeWithText(text(SettingsR.string.update_channel_debug)).assertDoesNotExist()
    }

    @Test fun scrollingReachesActionsAndBusyDisablesCheckAndDownload() {
        val hash = "b".repeat(64)
        candidate = UpdateCandidate(
            UpdateManifest(1, "app.example", 11, "1.3.0", UpdateChannel.STABLE, "a".repeat(40), 31,
                emptyList(), hash, hash, 100, "app.apk", "refs/tags/v1.3.0"),
            "https://example.com/app.apk", "Release notes",
        )
        showScreen()
        val check = compose.onNodeWithText(text(SettingsR.string.update_check_now))
        val download = compose.onNodeWithText(text(SettingsR.string.update_download_install))
        check.performScrollTo().performClick()
        download.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, downloads); busy = true; downloading = true }
        check.performScrollTo().assertIsNotEnabled()
        download.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(SettingsR.string.update_cancel_download)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }
}
